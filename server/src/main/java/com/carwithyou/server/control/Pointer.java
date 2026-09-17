package com.carwithyou.server.control;

/**
 * 一个触摸指针（一根手指）。
 *
 * {@code id} 是车机端在协议里用的指针编号；{@code localId} 是本地映射成的最小可用
 * 序号，直接用作 PointerProperties 数组下标和 pointerId —— 保证数组永远是稠密的，
 * 不会因为远端指针对端发来一个很大的 id 而把数组撑爆。
 */
public final class Pointer {

    /** 车机端发来的指针编号。 */
    public final int id;

    /** 本地最小编号（按需分配），数组下标和 PointerProperties.id 用它。 */
    public final int localId;

    public float x;
    public float y;
    public float pressure = 1f;

    /** 已抬起（在 update() 填完数组后由 cleanUp 移除）。 */
    public boolean up;

    public Pointer(int id, int localId) {
        this.id = id;
        this.localId = localId;
    }
}