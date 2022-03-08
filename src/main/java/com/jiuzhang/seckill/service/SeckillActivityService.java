package com.jiuzhang.seckill.service;

import com.alibaba.fastjson.JSON;
import com.jiuzhang.seckill.db.dao.OrderDao;
import com.jiuzhang.seckill.db.dao.SeckillActivityDao;
import com.jiuzhang.seckill.db.dao.SeckillCommodityDao;
import com.jiuzhang.seckill.db.po.Order;
import com.jiuzhang.seckill.db.po.SeckillActivity;
import com.jiuzhang.seckill.db.po.SeckillCommodity;
import com.jiuzhang.seckill.mq.RocketMQService;
import com.jiuzhang.seckill.util.RedisService;
import com.jiuzhang.seckill.util.SnowFlake;
import com.sun.org.apache.xpath.internal.operations.Or;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
public class SeckillActivityService {

    @Autowired
    private RedisService redisService;

    @Autowired
    private SeckillActivityDao seckillActivityDao;

    @Autowired
    private RocketMQService rocketMQService;

    @Autowired
    SeckillCommodityDao seckillCommodityDao;

    @Autowired
    OrderDao orderDao;

    /**
     * datacenterId;  数据中心
     * machineId;     机器标识
     * 在分布式环境中可以从机器配置上读取
     * 单机开发环境中先写死
     */
    private final SnowFlake snowFlake = new SnowFlake(1, 1);

    /**
     * Create order.
     *
     * @param id Activity ID
     * @param userId User ID
     * @return Order detail
     * @throws Exception MQ exception
     */
    public Order createOrder(long id, long userId) throws Exception {

        // 1. query & get activity
        SeckillActivity seckillActivity = seckillActivityDao.querySeckillActivityById(id);

        // 2. create & set new order information
        Order order = new Order();

        // use snowflake algorithm to generate order ID
        order.setOrderNo(String.valueOf(snowFlake.nextId()));
        order.setSeckillActivityId(seckillActivity.getId());
        order.setUserId(userId);
        order.setOrderAmount(seckillActivity.getSeckillPrice().longValue());

        // 3. send "create order" message to Rocket MQ
        rocketMQService.sendMessage("seckill_order", JSON.toJSONString(order));

        // 4. send "validate pay status" message to Rocket MQ
        // Rocket MQ support 18 levels, messageDelayLevel=1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
        rocketMQService.sendDelayMessage("pay_check", JSON.toJSONString(order), 5);

        return order;
    }

    /**
     * 判断商品是否还有库存
     *
     * @param activityId 商品ID
     * @return
     */
    public boolean seckillStockValidator(long activityId) {
        String key = "stock:" + activityId;
        return redisService.stockDeductValidator(key);
    }


    /**
     * 将秒杀详情相关信息倒入redis
     *
     * @param seckillActivityId
     */
    public void pushSeckillInfoToRedis(long seckillActivityId) {
        SeckillActivity seckillActivity = seckillActivityDao.querySeckillActivityById(seckillActivityId);
        redisService.setValue("seckillActivity:" + seckillActivityId, JSON.toJSONString(seckillActivity));

        SeckillCommodity seckillCommodity = seckillCommodityDao.querySeckillCommodityById(seckillActivity.getCommodityId());
        redisService.setValue("seckillCommodity:" + seckillActivity.getCommodityId(), JSON.toJSONString(seckillCommodity));
    }

    /**
     * 订单支付完成处理
     *
     * @param orderNo
     */
    public void payOrderProcess(String orderNo) throws Exception {
        log.info("完成支付订单  订单号：" + orderNo);
        Order order = orderDao.queryOrder(orderNo);
        /*
         * 1.判断订单是否存在
         * 2.判断订单状态是否为未支付状态
         */
        if (order == null) {
            log.error("订单号对应订单不存在：" + orderNo);
            return;
        } else if(order.getOrderStatus() != 1 ) {
            log.error("订单状态无效：" + orderNo);
            return;
        }
        /*
         * 2.订单支付完成
         */
        order.setPayTime(new Date());
        //订单状态 0:没有可用库存，无效订单 1:已创建等待付款 ,2:支付完成
        order.setOrderStatus(2);
        orderDao.updateOrder(order);
        /*
         * 3.发送订单付款成功消息
         */
        rocketMQService.sendMessage("pay_done", JSON.toJSONString(order));
    }
}
