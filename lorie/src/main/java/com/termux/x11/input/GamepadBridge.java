package com.termux.x11.input;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Driveclub Android port: passes a game controller (the AYN Thor's built-in one) to a program in
 * Termux or a proot distribution as a small UDP packet on 127.0.0.1, with analog sticks and
 * triggers. X11 has no gamepad support, so without this every controller button reaches X as the
 * same unknown key.
 *
 * Packet (little endian, 32 bytes): "BBPD", u16 version 1, u16 0, u32 sequence, u32 buttons
 * (bit 0 A, 1 B, 2 X, 3 Y, 4 L1, 5 R1, 6 L2, 7 R2, 8 select, 9 start, 10 L3, 11 R3, 12 up,
 * 13 down, 14 left, 15 right, 16 guide), s16 left x, left y, right x, right y, u16 left trigger,
 * right trigger, 4 bytes zero. Sent on every change and every 100 ms while a controller is in use.
 */
public final class GamepadBridge {
    public static final int PORT = 47998;

    private static final Object lock = new Object();
    private static int buttons;
    private static short lx, ly, rx, ry;
    private static int lt, rt;
    private static int hatButtons;
    private static int sequence;
    private static boolean active;
    private static ScheduledExecutorService sender;
    private static DatagramSocket socket;
    private static InetAddress loopback;

    private GamepadBridge() {}

    private static boolean isGamepad(InputDevice device, int source) {
        final int sources = device != null ? device.getSources() : source;
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static int bitFor(int keyCode, boolean fromGamepad) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 1;
            case KeyEvent.KEYCODE_BUTTON_B: return 1 << 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 1 << 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 1 << 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 1 << 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 1 << 5;
            case KeyEvent.KEYCODE_BUTTON_L2: return 1 << 6;
            case KeyEvent.KEYCODE_BUTTON_R2: return 1 << 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 1 << 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 1 << 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 1 << 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 1 << 11;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 1 << 16;
            default: break;
        }
        if (!fromGamepad) return 0;
        switch (keyCode) {
            // From a controller only: a keyboard's arrows and Back stay keyboard keys.
            case KeyEvent.KEYCODE_DPAD_UP: return 1 << 12;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 1 << 13;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 1 << 14;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 1 << 15;
            case KeyEvent.KEYCODE_BACK: return 1 << 8;
            default: return 0;
        }
    }

    /** True when the event belonged to a controller and was taken (not passed to X). */
    public static boolean onKey(KeyEvent e) {
        final boolean fromGamepad = isGamepad(e.getDevice(), e.getSource());
        final int bit = bitFor(e.getKeyCode(), fromGamepad);
        if (bit == 0) return false;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return true;
        synchronized (lock) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) buttons |= bit;
            else if (e.getAction() == KeyEvent.ACTION_UP) buttons &= ~bit;
            else return true;
        }
        changed();
        return true;
    }

    private static float axis(MotionEvent e, int axis) {
        return e.getAxisValue(axis);
    }

    private static short stick(float v) {
        final float c = Math.max(-1f, Math.min(1f, v));
        return (short) Math.round(c * 32767f);
    }

    private static int trigger(float v) {
        final float c = Math.max(0f, Math.min(1f, v));
        return Math.round(c * 65535f);
    }

    /** True when the event came from a controller's sticks, triggers or hat and was taken. */
    public static boolean onMotion(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_MOVE) return false;
        if (!isGamepad(e.getDevice(), e.getSource())) return false;
        if ((e.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false;
        final InputDevice device = e.getDevice();
        // Right stick: Z/RZ on most Android controllers, RX/RY on some.
        boolean zrz = device == null || device.getMotionRange(MotionEvent.AXIS_Z, e.getSource()) != null;
        final float hatX = axis(e, MotionEvent.AXIS_HAT_X), hatY = axis(e, MotionEvent.AXIS_HAT_Y);
        int hat = 0;
        if (hatY < -0.5f) hat |= 1 << 12;
        if (hatY > 0.5f) hat |= 1 << 13;
        if (hatX < -0.5f) hat |= 1 << 14;
        if (hatX > 0.5f) hat |= 1 << 15;
        synchronized (lock) {
            lx = stick(axis(e, MotionEvent.AXIS_X));
            ly = stick(axis(e, MotionEvent.AXIS_Y));
            rx = stick(axis(e, zrz ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX));
            ry = stick(axis(e, zrz ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY));
            lt = trigger(Math.max(axis(e, MotionEvent.AXIS_LTRIGGER), axis(e, MotionEvent.AXIS_BRAKE)));
            rt = trigger(Math.max(axis(e, MotionEvent.AXIS_RTRIGGER), axis(e, MotionEvent.AXIS_GAS)));
            hatButtons = hat;
        }
        changed();
        return true;
    }

    private static void changed() {
        synchronized (lock) {
            active = true;
            if (sender == null) {
                sender = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "GamepadBridge");
                    t.setDaemon(true);
                    return t;
                });
                // Keep-alive: the receiver treats a silent controller as gone after 0.5 s.
                sender.scheduleWithFixedDelay(GamepadBridge::send, 100, 100, TimeUnit.MILLISECONDS);
            }
        }
        sender.execute(GamepadBridge::send);
    }

    private static void send() {
        final byte[] data = new byte[32];
        synchronized (lock) {
            if (!active) return;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte) 'B').put((byte) 'B').put((byte) 'P').put((byte) 'D');
            b.putShort((short) 1).putShort((short) 0);
            b.putInt(++sequence);
            b.putInt(buttons | hatButtons);
            b.putShort(lx).putShort(ly).putShort(rx).putShort(ry);
            b.putShort((short) lt).putShort((short) rt);
        }
        try {
            if (socket == null) {
                socket = new DatagramSocket();
                loopback = InetAddress.getByName("127.0.0.1");
            }
            socket.send(new DatagramPacket(data, data.length, loopback, PORT));
        } catch (Exception ignored) {
            // Nobody listening, or the network stack is not ready: the next state tries again.
        }
    }
}
