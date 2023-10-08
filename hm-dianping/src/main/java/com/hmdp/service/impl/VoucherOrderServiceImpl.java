package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

    }

    private IVoucherOrderService proxy;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        // 2.2类初始化完就马上执行该阻塞队列任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    String queueName = "stream.orders";

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 如果取不到队列的消息就重复
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 解析数据(因为count取1，所以0就是我们的数据)
                    // string是消息的id，两个object分别是lua脚本写的命名和数据(userId,voucherId,orderId)
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> values = map.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 保存订单到数据库
                    handleVoucherOrder(voucherOrder);

                    // ACK确认(队列名，组名，消息id)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 处理pending-list， xreadgroup group g1 c1 count 1 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")
                            ));
                    // 如果取不到pending的消息就结束，因为没未确认的消息了
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 解析数据(因为count取1，所以0就是我们的数据)
                    // string是消息的id，两个object分别是lua脚本写的命名和数据(userId,voucherId,orderId)
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> values = map.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 保存订单到数据库
                    handleVoucherOrder(voucherOrder);

                    // ACK确认(队列名，组名，消息id)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());

                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    // 这里不用递归，因为只要pending-list里有数据，就不会触发break结束循环
                    try {
                        // 如果怕出错了，一直重试的频率太高就写个休眠
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }
    /*
    // 2.1创建单个线程池
    // 1.2创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 2.3创建匿名类
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            // 2.4取出阻塞队列的数据进行保存到数据库
            while (true){
                try {
                    // 这里如果没数据会一直阻塞在这个取的方法，所以不用担心性能消耗
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.5编写保存订单的方法
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
     */

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 2.6将之前seckillVoucher从获取锁开始的代码弄过来修改成这样了
        // 因为是异步调用，无法用之前的方法拿到userId，直接从传过来的订单里拿
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean success = lock.tryLock();
        if (!success) {
            // 因为是异步调用, 所以返回的结果前端也搜不到，就梅弄返回值
            log.error("同个用户无法重复下单!");
            return;
        }

        try {
            // 这里本来是用代理对象去开启事务，但是又由于异步线程，是获取不到代理对象的
            // 所以先在创建好订单那里，提前初始化了代理对象-->2.7
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int i = result.intValue();
        if (i != 0) {
            return Result.fail(result == 1 ? "库存不足！" : "无法重复下单！");
        }

        // 2.7提前初始化代理对象 (proxy是成员变量)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int i = result.intValue();
        if (i != 0) {
            return Result.fail(result == 1 ? "库存不足！" : "无法重复下单！");
        }
        // 如果result为0，则获取订单号
        long order = redisWorker.nextId("order");

        // 1.将优惠券id和用户id封装进去阻塞队列 2.然后实现异步下单
        // 1.1创建订单(需要订单id，优惠券id，用户id)
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(order);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 1.3将订单存入阻塞队列
        orderTasks.add(voucherOrder);

        // 2.7提前初始化代理对象 (proxy是成员变量)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(order);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 2.8这里是把之前的createVoucherOrder改成这样，把参数改成了VoucherOrder
        // 这里的各种id同样在VoucherOrder里取出来
        int count = query().eq("user_id", voucherOrder.getUserId()).count();
        if (count > 0) {
            // 同样因为是异步调用, 所以返回的结果前端也搜不到，就梅弄返回值
            log.error("该用户已购买过！");
            return;
        }
        // 判断库存是否充足，充足则-1
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();

        if (!success) {
            // 同样因为是异步调用, 所以返回的结果前端也搜不到，就梅弄返回值
            log.error("库存不足！");
            return;
        }
        // 保存订单到数据库，任务完成！
        save(voucherOrder);
    }

/*  @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("抢购未开始！");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束！");
        }

        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }


//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        }
        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock lock = new SimpleRedisLock("order", stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order");
        boolean success = lock.tryLock();
        if (!success){
            return Result.fail("同个用户无法重复下单!");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } finally {
            lock.unlock();
        }
    }*/

/*
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {

        int count = query().eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("该用户已购买过！");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("库存不足！");
        }

        //如果成功就创建订单(需要订单id，优惠券id，用户id)
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderID = redisWorker.nextId("order");
        voucherOrder.setId(orderID);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderID);

    }*/
}
