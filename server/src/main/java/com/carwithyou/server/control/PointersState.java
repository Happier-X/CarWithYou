package com.carwithyou.server.control;

import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 多点触控状态。
 *
 * 核心顺序（自 scrcpy）：抬起时**先标记 up，update() 把全部指针填进数组并返回数量，
 * 再 cleanUp 移除已抬起的指针**。这样 ACTION_UP / ACTION_POINTER_UP 的事件里
 * 指针数量不会在构建前就少算一个，多指抬起才不会崩。
 */
public final class PointersState {

    public static final int MAX_POINTERS = 10;

    private final List<Pointer> pointers = new ArrayList<>();

    private int indexOf(int id) {
        for (int i = 0; i < pointers.size(); i++) {
            if (pointers.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    public boolean isExisting(int id) {
        return indexOf(id) != -1;
    }

    private boolean isLocalIdAvailable(int localId) {
        for (Pointer pointer : pointers) {
            if (pointer.localId == localId) {
                return false;
            }
        }
        return true;
    }

    private int nextUnusedLocalId() {
        for (int localId = 0; localId < MAX_POINTERS; localId++) {
            if (isLocalIdAvailable(localId)) {
                return localId;
            }
        }
        return -1;
    }

    public Pointer get(int index) {
        return pointers.get(index);
    }

    /**
     * 按车机端指针 id 取本地指针；不存在就新建一个。数组满了返回 -1。
     */
    public int getPointerIndex(int id) {
        int index = indexOf(id);
        if (index != -1) {
            return index;
        }
        if (pointers.size() >= MAX_POINTERS) {
            return -1;
        }
        int localId = nextUnusedLocalId();
        if (localId == -1) {
            throw new AssertionError("size() < MAX_POINTERS 时必然有可用 localId");
        }
        pointers.add(new Pointer(id, localId));
        return pointers.size() - 1;
    }

    /**
     * 把当前全部指针刷进 props/coords。
     *
     * @return 刷入的指针数（包含仍待清理的抬起指针）
     */
    public int update(MotionEvent.PointerProperties[] props, MotionEvent.PointerCoords[] coords) {
        int count = pointers.size();
        for (int i = 0; i < count; i++) {
            Pointer pointer = pointers.get(i);
            props[i].id = pointer.localId;
            coords[i].x = pointer.x;
            coords[i].y = pointer.y;
            coords[i].pressure = pointer.pressure;
        }
        cleanUp();
        return count;
    }

    /** 移除所有已抬起（up）的指针。 */
    private void cleanUp() {
        for (int i = pointers.size() - 1; i >= 0; i--) {
            if (pointers.get(i).up) {
                pointers.remove(i);
            }
        }
    }

    public int size() {
        return pointers.size();
    }
}