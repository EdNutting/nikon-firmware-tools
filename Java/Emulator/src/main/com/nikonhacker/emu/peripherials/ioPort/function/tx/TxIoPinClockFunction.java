package com.nikonhacker.emu.peripherials.ioPort.function.tx;

import com.nikonhacker.Constants;
import com.nikonhacker.emu.peripherials.ioPort.function.AbstractOutputPinFunction;

public class TxIoPinClockFunction  extends AbstractOutputPinFunction {

    public TxIoPinClockFunction() {
        super(Constants.CHIP_LABEL[Constants.CHIP_TX]);
    }

    @Override
    public String getFullName() {
        return componentName + " Clock";
    }

    @Override
    public String getShortName() {
        return "SCOUT";
    }

    @Override
    public Integer getValue(Integer defaultOutputValue) {
        if (logPinMessages) System.out.println("TxIoPinClockFunction.getValue not implemented for pin " + getShortName());
        return null;
    }
}
