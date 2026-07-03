package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        // 1. 从redis中查询商铺缓存
        String cacheJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheJson)) {
            // 2. 存在，直接返回
            List<ShopType> shopTypes = JSONUtil.toList(cacheJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        // 3. 不存在，根据id查询数据库
        List<ShopType> shopTypes = baseMapper.selectList(null);
        // 4. 缓存到redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes), RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 5. 返回结果
        return Result.ok(shopTypes);

    }
}
