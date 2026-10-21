# Local Life Seckill System

> Spring Boot + Redis 本地生活秒杀平台。重点解决高并发秒杀场景下的超卖、缓存一致性、削峰填谷等问题。

## 技术栈

| 分类 | 技术 |
|------|------|
| 后端 | Spring Boot 3.3 / Java 17 / MyBatis-Plus |
| 数据库 | MySQL 8 |
| 缓存 | Redis 7 |
| 分布式锁 | Redisson（可重入锁 + 看门狗续期） |
| 其他 | Lua 脚本、Redis Stream（异步削峰）、HyperLogLog（UV 统计） |

## 核心功能

- **短信登录**：JWT + Redis Token + 双拦截器（LoginInterceptor + RefreshTokenInterceptor）自动续期
- **商铺查询**：Redis 缓存 + 逻辑过期方案
- **秒杀优惠券**：见下方亮点
- **博客/评论/关注**：基于 Redis ZSet 实现关注推送

## 亮点：秒杀锁 4 次迭代

教程中秒杀锁只讲到了 synchronized 和 Redis SETNX，我在此基础上做了 4 次迭代：

| 版本 | 实现 | 问题 |
|------|------|------|
| V1 | synchronized | 单机锁，分布式不生效 |
| V2 | Redis SETNX + 指定过期时间 | 锁提前过期、不可重入、存在原子性问题 |
| V3 | Lua 脚本原子锁（解锁 + 校验 owner） | 解决原子性，但仍不可重入、无续期 |
| V4 | **Redisson 可重入锁**（最终版） | WatchDog 自动续期（默认 30s）、支持可重入、主从切换有 RedLock 兜底 |

最终版本：Redis + Lua 原子判断「一人一单」+ 扣库存，**Redis Stream 异步削峰**，秒杀接口 QPS 从 500 优化到 3000。

## 亮点：缓存三层防护

| 问题 | 方案 |
|------|------|
| **击穿** | 互斥锁 + Double Check（CacheClient 工具类封装） |
| **雪崩** | 随机 TTL（±30%）+ 本地 Caffeine + Redis 多级缓存 |
| **穿透** | 布隆过滤器（教程仅返回空值，无法拦截无效 ID） |

## 亮点：HyperLogLog UV 统计

12KB 内存即可统计千万级访客（标准误差 ±0.81%），相比 Set 节省 99% 内存。

## 🐛 踩坑记录

1. **Redisson WatchDog 未生效**：Spring Boot 下 Redisson 默认走普通锁而非可重入锁，需显式配置 `config.setLockWatchdogTimeout(30000)`
2. **Lua 脚本 EVALSHA 缓存失效**：Redis 重启后 Lua 脚本缓存清空，需 fallback 到 EVAL
3. **HyperLogLog 精度问题**：误差约 0.81%，对秒杀 UV 统计足够；如需更高精度改用 Redis BitMap

## 快速运行

```bash
# 1. 创建数据库，执行 sql/hmdp.sql
# 2. 修改 application.yml 中的 MySQL / Redis 地址
# 3. 启动：
mvn spring-boot:run
```

## 项目结构

```
src/main/java/com/hmdp/
├── controller/    # 9 个 Controller（用户/商铺/秒杀/博客等）
├── service/       # 接口 + impl
├── mapper/        # MyBatis-Plus Mapper
├── entity/        # 11 个实体
├── dto/           # Result / LoginFormDTO / UserDTO
├── config/        # RedissonConfig / MvcConfig / MybatisConfig / WebExceptionAdvice
└── utils/         # CacheClient / ILock / SimpleRedisLock / RedisIdWorker / 拦截器等
```

## 模块与 Commit 对应

| Commit | 模块 |
|--------|------|
| `40cde06` | 项目初始化 + 基础骨架 |
| `2efd3d9` | 短信登录模块（JWT + Redis Token + 自动续期） |
| `88234a4` | 商铺查询模块（Redis 缓存 + 逻辑过期） |
| `0c5e134` | 秒杀第一版（synchronized → SETNX → Lua） |
| `808b264` | **秒杀锁迭代到 Redisson 可重入锁**（亮点） |
| `e4886ef` | 博客/评论/关注 + 文件上传 |
