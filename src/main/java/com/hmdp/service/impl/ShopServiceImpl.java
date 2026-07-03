package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //1.缓存穿透
        Shop shop = cacheClient.quryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        //2.互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //3. 逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //5. 不存在, 返回错误
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
       //7. 返回成功
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+ id);
        //3. 返回成功
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 根据坐标查询
        String key = SHOP_GEO_KEY + typeId;
        // 2. 根据坐标查询
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x,y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 解析出id
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        // 4.1 截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5. 根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6. 返回成功
        return Result.ok(shops);

    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从缓存中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            //返回空对象
            return null;
        }
        //3.不存在，实现缓存重建
        //4.尝试获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //5.获取锁成功
            if (!isLock) {
                //获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // Double Check
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //6.获取锁成功，根据id查询数据库
            shop = getById(id);
            //7.不存在，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //8.存在，写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //9.释放锁
            unlock(lockKey);
        }
        //10.返回成功
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
