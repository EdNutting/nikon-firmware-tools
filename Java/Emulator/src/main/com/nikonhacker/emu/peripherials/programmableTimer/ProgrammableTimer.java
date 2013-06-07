package com.nikonhacker.emu.peripherials.programmableTimer;

import com.nikonhacker.emu.peripherials.interruptController.InterruptController;

import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class ProgrammableTimer {

    /** Lower boundary of sustainable interval between emulator scheduler ticks */
    public final static int MIN_EMULATOR_INTERVAL_NANOSECONDS = 50000; //50 microseconds interval = max frequency of 20kHz

    /** This is the number of this timer */
    protected int timerNumber;

    /** This is the current value of this timer */
    protected int currentValue;

    /** InterruptController is passed to constructor to be able to actually trigger requests */
    protected InterruptController interruptController;

    /**
     * This is an emulator setting allowing to pause timers even though their emulated state is enabled.
     * The Java timer continues to run, but each execution does nothing
     */
    protected boolean active = false;

    /** Underlying scheduler */
    protected ScheduledExecutorService executorService;

    /**
     * The scale compensates for the fact that emulated clocks cannot run faster than MAX_EMULATOR_FREQUENCY
     * So emulated timers running faster are triggered at a (emulated frequency / scale)
     * And at each trigger, the reload counter is inc/decremented by (scale)
     */
    protected int scale;

    protected TimerTask timerTask = null;

    protected long intervalNanoseconds = 1000000000L; // in ns/Timertick. For example, intervalNanoseconds=1000000000 ns/Timertick means f = 1Hz

    private TimerCycleCounterListener cycleCounterListener;

    public ProgrammableTimer(int timerNumber, InterruptController interruptController, TimerCycleCounterListener cycleCounterListener) {
        this.timerNumber = timerNumber;
        this.interruptController = interruptController;
        this.cycleCounterListener = cycleCounterListener;
    }

    public int getTimerNumber() {
        return timerNumber;
    }

    public int getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(int currentValue) {
        this.currentValue = currentValue;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    public TimerTask getTimerTask() {
        return timerTask;
    }

    protected void scheduleTask() {
        if (cycleCounterListener != null) {
            cycleCounterListener.registerTimer(this, intervalNanoseconds);
        }
        else {
            executorService.scheduleAtFixedRate(timerTask, 0, intervalNanoseconds, TimeUnit.NANOSECONDS);
        }
    }

    protected void unscheduleTask() {
        if (cycleCounterListener != null) {
            cycleCounterListener.unregisterTimer(this);
        }
        else {
            executorService.shutdownNow();
        }
    }


    @Override
    public String toString() {
        return "ProgrammableTimer #" + timerNumber + (active?" (active)":" (inactive)");
    }

    public void increment() {
        timerTask.run();
    }
}
