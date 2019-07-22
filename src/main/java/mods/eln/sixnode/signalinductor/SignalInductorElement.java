package mods.eln.sixnode.signalinductor;

import mods.eln.misc.Direction;
import mods.eln.misc.LRDU;
import mods.eln.misc.Utils;
import mods.eln.node.six.SixNode;
import mods.eln.node.six.SixNodeDescriptor;
import mods.eln.node.six.SixNodeElement;
import mods.eln.sim.mna.state.ElectricalLoad;
import mods.eln.sim.nbt.NbtInductor;
import mods.eln.sim.thermal.ThermalLoad;
import mods.eln.sim.nbt.NbtElectricalLoad;

public class SignalInductorElement extends SixNodeElement {

    public SignalInductorDescriptor descriptor;
    public NbtElectricalLoad postiveLoad = new NbtElectricalLoad("postiveLoad");
    public NbtElectricalLoad negativeLoad = new NbtElectricalLoad("negativeLoad");
    public NbtInductor inductor = new NbtInductor("inductor", postiveLoad, negativeLoad);

    public SignalInductorElement(SixNode sixNode, Direction side, SixNodeDescriptor descriptor) {
        super(sixNode, side, descriptor);
        electricalLoadList.add(postiveLoad);
        electricalLoadList.add(negativeLoad);
        electricalComponentList.add(inductor);
        postiveLoad.setAsMustBeFarFromInterSystem();
        this.descriptor = (SignalInductorDescriptor) descriptor;
    }

    @Override
    public ElectricalLoad getElectricalLoad(LRDU lrdu, int mask) {
        if (front == lrdu) return postiveLoad;
        if (front.inverse() == lrdu) return negativeLoad;
        return null;
    }

    @Override
    public ThermalLoad getThermalLoad(LRDU lrdu, int mask) {
        return null;
    }

    @Override
    public int getConnectionMask(LRDU lrdu) {
        if (front == lrdu) return descriptor.cable.getNodeMask();
        if (front.inverse() == lrdu) return descriptor.cable.getNodeMask();
        return 0;
    }

    @Override
    public String multiMeterString() {
        return Utils.plotAmpere("I", inductor.getCurrent());
    }

    @Override
    public String thermoMeterString() {
        return "";
    }

    @Override
    public void initialize() {
        descriptor.applyTo(negativeLoad);
        descriptor.applyTo(postiveLoad);
        descriptor.applyTo(inductor);
    }
}
