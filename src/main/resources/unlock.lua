-- 判断线程标识是否一致，防止误删
if(redis.call("GET",KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call("DEL",KEYS[1])
end
return 0;