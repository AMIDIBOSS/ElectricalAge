package mods.eln.sixnode.electricalcable;

import mods.eln.Eln;
import mods.eln.generic.GenericItemUsingDamageDescriptor;
import mods.eln.i18n.I18N;
import mods.eln.item.BrushDescriptor;
import mods.eln.misc.Direction;
import mods.eln.misc.LRDU;
import mods.eln.misc.Utils;
import mods.eln.node.NodeBase;
import mods.eln.node.six.SixNode;
import mods.eln.node.six.SixNodeDescriptor;
import mods.eln.node.six.SixNodeElement;
import mods.eln.sim.mna.state.ElectricalLoad;
import mods.eln.sim.thermal.ThermalLoad;
import mods.eln.sim.nbt.NbtElectricalLoad;
import mods.eln.sim.nbt.NbtThermalLoad;
import mods.eln.sim.thermal.ThermalLoadWatchDog;
import mods.eln.sim.mna.VoltageStateWatchDog;
import mods.eln.sim.destruct.WorldExplosion;
import mods.eln.sim.thermal.ElectricalLoadHeatThermalLoad;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ElectricalCableElement extends SixNodeElement {

    public ElectricalCableDescriptor descriptor;

    public NbtElectricalLoad electricalLoad = new NbtElectricalLoad("electricalLoad");
    NbtThermalLoad thermalLoad = new NbtThermalLoad("thermalLoad");

    ElectricalLoadHeatThermalLoad heater = new ElectricalLoadHeatThermalLoad(electricalLoad, thermalLoad);
    ThermalLoadWatchDog thermalWatchdog = new ThermalLoadWatchDog();
    VoltageStateWatchDog voltageWatchdog = new VoltageStateWatchDog();

    int color;
    int colorCare;

    public ElectricalCableElement(SixNode sixNode, Direction side, SixNodeDescriptor descriptor) {
        super(sixNode, side, descriptor);
        this.descriptor = (ElectricalCableDescriptor) descriptor;
        color = 0;
        colorCare = 1;
        electricalLoad.setCanBeSimplifiedByLine(true);
        electricalLoadList.add(electricalLoad);

        if (!this.descriptor.signalWire) {
            thermalLoadList.add(thermalLoad);
            thermalSlowProcessList.add(heater);
            thermalLoad.setAsSlow();
            slowProcessList.add(thermalWatchdog);
            thermalWatchdog
                .set(thermalLoad)
                .setLimit(this.descriptor.thermalWarmLimit, this.descriptor.thermalCoolLimit)
                .set(new WorldExplosion(this).cableExplosion());
        }

        slowProcessList.add(voltageWatchdog);
        voltageWatchdog
            .set(electricalLoad)
            .setUNominal(this.descriptor.electricalNominalVoltage)
            .set(new WorldExplosion(this).cableExplosion());


    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        byte b = nbt.getByte("color");
        color = b & 0xF;
        colorCare = (b >> 4) & 1;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("color", (byte) (color + (colorCare << 4)));
    }

    @Override
    public ElectricalLoad getElectricalLoad(LRDU lrdu, int mask) {
        return electricalLoad;
    }

    @Override
    public ThermalLoad getThermalLoad(LRDU lrdu, int mask) {
        if (!descriptor.signalWire)
            return thermalLoad;
        else
            return null;
    }

    @Override
    public int getConnectionMask(LRDU lrdu) {
        return descriptor.getNodeMask() /*+ NodeBase.MASK_ELECTRICAL_WIRE*/ + (color << NodeBase.MASK_COLOR_SHIFT) + (colorCare << NodeBase.MASK_COLOR_CARE_SHIFT);
    }

    @Override
    public String multiMeterString() {
        if (!descriptor.signalWire)
            return Utils.plotUIP(electricalLoad.getU(), electricalLoad.getI());
        else
            return Utils.plotSignal(electricalLoad.getU(), electricalLoad.getI());
    }

    @Override
    public Map<String, String> getWaila() {
        Map<String, String> info = new HashMap<String, String>();

        if (descriptor.signalWire) {
            info.put(I18N.tr("Signal Voltage"), Utils.plotVolt("", electricalLoad.getU()));
        } else {
            info.put(I18N.tr("Current"), Utils.plotAmpere("", electricalLoad.getI()));
            info.put(I18N.tr("Temperature"), Utils.plotCelsius("", thermalLoad.getT()));
            if (Eln.wailaEasyMode) {
                info.put(I18N.tr("Voltage"), Utils.plotVolt("", electricalLoad.getU()));
            }
        }

        try {
            int subSystemSize = electricalLoad.getSubSystem().matrixSize();
            String textColor = "";
            if (subSystemSize <= 8) {
                textColor = "§a";
            } else if (subSystemSize <= 15) {
                textColor = "§6";
            } else {
                textColor = "§c";
            }
            info.put(I18N.tr("Subsystem Matrix Size: "), textColor + subSystemSize);


        } catch (Exception e) {
            info.put(I18N.tr("Subsystem Matrix Size: "), "§cNot part of a subsystem!?");
        }

        return info;
    }

    @Override
    public String thermoMeterString() {
        if (!descriptor.signalWire)
            return Utils.plotCelsius("T", thermalLoad.Tc);
        else
            return null;
    }

    @Override
    public void networkSerialize(DataOutputStream stream) {
        super.networkSerialize(stream);
        try {
            stream.writeByte(color << 4);
        /*	stream.writeShort((short) (electricalLoad.Uc * NodeBase.NETWORK_SERIALIZE_U_FACTOR));
	    	stream.writeShort((short) (electricalLoad.getCurrent() * NodeBase.NETWORK_SERIALIZE_I_FACTOR));
	    	stream.writeShort((short) (thermalLoad.Tc * NodeBase.NETWORK_SERIALIZE_T_FACTOR));*/
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void initialize() {
        descriptor.applyTo(electricalLoad);
        descriptor.applyTo(thermalLoad);
        //heater.setDeltaTPerSecondMax(30);
    }

    @Override
    public boolean onBlockActivated(EntityPlayer entityPlayer, Direction side, float vx, float vy, float vz) {
	/*	World w = sixNode.coordonate.world();
		boolean exist = w.blockExists(10000, 0, 0);
		int id = w.getBlockId(10000, 0, 0);*/
        ItemStack currentItemStack = entityPlayer.getCurrentEquippedItem();
        //int i;
        if (Utils.isPlayerUsingWrench(entityPlayer)) {
            colorCare = colorCare ^ 1;
            Utils.addChatMessage(entityPlayer, "Wire color care " + colorCare);
            sixNode.reconnect();
        } else if (currentItemStack != null) {
            Item item = currentItemStack.getItem();

            GenericItemUsingDamageDescriptor gen = BrushDescriptor.getDescriptor(currentItemStack);
            if (gen != null && gen instanceof BrushDescriptor) {
                BrushDescriptor brush = (BrushDescriptor) gen;
                int brushColor = brush.getColor(currentItemStack);
                if (brushColor != color && brush.use(currentItemStack, entityPlayer)) {
                    color = brushColor;
                    sixNode.reconnect();
                }
            }
        }
        return false;
    }
}
