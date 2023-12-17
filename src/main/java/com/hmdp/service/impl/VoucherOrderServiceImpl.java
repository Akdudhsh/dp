package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 罗蓉鑫
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /* 阻塞队列,用于存放生成的订单信息 */
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue(1024 * 1024);
    private final static ExecutorService SECKILL_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private String stream_key = "stream.orders";
    /* 在对象初始化完成就执行 */
    @PostConstruct
    private void init(){
        SECKILL_EXECUTOR.submit(new VoucherOrderHandle());
    }
    /* 完成异步下单的线程*/
    private class VoucherOrderHandle implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的消息 xreadgroup group g1 c1 count 1 blocking 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(stream_key, ReadOffset.lastConsumed()));
                    //2.获取失败，没有消息，进行下一次获取
                    if (list == null || list.isEmpty()){
                        continue;
                    }
                    //3.获取成功，可以下单
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //4.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(stream_key,"g1",record.getId());
                } catch (Exception e) {
                   handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1.获取pendingList中的消息 xreadgroup group g1 c1 count 1  streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(stream_key, ReadOffset.from("0")));
                    //2.获取失败，说明pendingList中没有未确认的消息
                    if (list == null || list.isEmpty()){
                        break;
                    }
                    //3.获取成功，可以下单
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //4.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(stream_key,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
    /*private class VoucherOrderHandle implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //从阻塞队列中获取订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("执行异步下单失败",e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();
        //获取redisson实现的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        //指定参数 再指定时间内会进行重试，锁的超时自动释放时间，时间单位
        //不指定参数，不会进行重试，锁的超时时间为30s
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!isLock){
            log.error("用户已下单");
            return;
        }
        try {
            proxy.createOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //生成订单号
        long orderId = redisIDWorker.nextInt("order");
        //1.执行秒杀脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId));
        //2.判断秒杀结果 是否为0
        int r = result.intValue();
        //2.1不为0，说明秒杀失败
        if (r !=0 ) {
            return Result.fail(r == 1 ? "库存不足" : "你已经抢购过了");
        }
        //2.2为0，说明秒杀成功
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //5.返回结果
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //1.执行秒杀脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString());
        //2.判断秒杀结果 是否为0
        int r = result.intValue();
        //2.1不为0，说明秒杀失败
        if (r !=0 ) {
            return Result.fail(r == 1 ? "库存不足" : "你已经抢购过了");
        }
        //2.2为0，说明秒杀成功

        //3.将优惠券id,用户id和订单id保存到阻塞队列
        long orderId = redisIDWorker.nextInt("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //3.1保存订单id
        voucherOrder.setId(orderId);
        //3.2保存用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //3.3保存优惠券id
        voucherOrder.setVoucherId(voucherId);
        //4.  异步保存订单信息
        //  将订单信息存入阻塞队列
        orderTasks.put(voucherOrder);
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //5.返回结果
        return Result.ok(orderId);
    }*/

//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //1.根据优惠券id获取秒杀优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还未开始！");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //！！！！！！
//        // 事务应在锁的范围内被提交，如果先释放锁，再提交的事务，可能也会导致线程安全问题，
//        // 锁已释放，但是事务还未提交，另一个线程拿到了事务还未提交的结果，造成一人多单
//        //！！！！！！
//        // 锁的对象不应该为this对象，而是应该锁的每个userId，让程序的性能更高
//        // 如果锁的是this对象，所有用户都需要都在串行执行这个方法，性能大大降低，这里只需要保证每一人一单
//        //！！！！！！
//        // 这里需要保证锁的范围应对于多个相同的用户线程的userId对象都相同，每一个线程创建的对象都new方式
//        // intern()方法保证了string对象转换成最原始的string线程池方式
//        // ！！！！！
//        // spring的事务是通过aop交给代理对象来执行的
//        // 这里方法是通过this（目标）对象调用的,会导致事务失效
//        // 所以需要通过代理对象来执行事务的方法
//        // 采用synchronized实现，面临分布式集群会出现线程安全问题
////        synchronized (userId.toString().intern()){
////
////        }
//        // 采用redis实现的分布式锁
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //获取redisson实现的分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        //指定参数 再指定时间内会进行重试，锁的超时自动释放时间，时间单位
//        //不指定参数，不会进行重试，锁的超时时间为30s
//        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        if(!isLock){
//            return Result.fail("你已经抢购过了");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }
    @Override
    @Transactional
    public Result createOrder(Long voucherId) {
        //5.实现一人一单的功能
        Long userId = UserHolder.getUser().getId();
        //查询同一个用户对于同一张秒杀优惠券的数量
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("已抢购过了");
        }
        //6.优惠券库存减1
        if (!seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0).update()) {
            return Result.fail("库存不足！");
        }
        //7.保存优惠券订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1保存订单id
        long orderId = redisIDWorker.nextInt("order");
        voucherOrder.setId(orderId);
        //7.2保存用户id
        voucherOrder.setUserId(userId);
        //7.3保存优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createOrder(VoucherOrder voucherOrder) {
        //查询同一个用户对于同一张秒杀优惠券的数量
        int count = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.info("已经抢购过了");
            return;
        }
        //6.优惠券库存减1
        if (!seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update()) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }
}
