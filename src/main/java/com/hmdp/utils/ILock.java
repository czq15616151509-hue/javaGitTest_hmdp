package com.hmdp.utils;

/**
 * @author chen
 * @function
 * @date 2025/10/20
 */
public interface ILock {
    //尝试获取锁
    boolean tryLock(long timeoutSeconds);
    //释放锁
    void unLock();
}
