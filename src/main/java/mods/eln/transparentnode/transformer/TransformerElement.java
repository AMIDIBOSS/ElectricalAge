package mods.eln.transparentnode.transformer;

import mods.eln.Eln;
import mods.eln.debug.DP;
import mods.eln.debug.DPType;
import mods.eln.i18n.I18N;
import mods.eln.item.ConfigCopyToolDescriptor;
import mods.eln.item.FerromagneticCoreDescriptor;
import mods.eln.item.IConfigurable;
import mods.eln.misc.Direction;
import mods.eln.misc.LRDU;
import mods.eln.misc.Utils;
import mods.eln.node.NodeBase;
import mods.eln.node.NodePeriodicPublishProcess;
import mods.eln.node.transparent.TransparentNode;
import mods.eln.node.transparent.TransparentNodeDescriptor;
import mods.eln.node.transparent.TransparentNodeElement;
import mods.eln.node.transparent.TransparentNodeElementInventory;
import mods.eln.sim.ElectricalLoad;
import mods.eln.sim.ThermalLoad;
import mods.eln.sim.mna.component.Transformer;
import mods.eln.sim.mna.component.VoltageSource;
import mods.eln.sim.mna.process.TransformerInterSystemProcess;
import mods.eln.sim.nbt.NbtElectricalLoad;
import mods.eln.sim.process.destruct.VoltageStateWatchDog;
import mods.eln.sim.process.destruct.WorldExplosion;
import mods.eln.sixnode.genericcable.GenericCableDescriptor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TransformerElement extends TransparentNodeElement implements IConfigurable {
    private final NbtElectricalLoad primaryLoad = new NbtElectricalLoad("primaryLoad");
    private final NbtElectricalLoad secondaryLoad = new NbtElectricalLoad("secondaryLoad");

    private final VoltageSource primaryVoltageSource = new VoltageSource("primaryVoltageSource");
    private final VoltageSource secondaryVoltageSource = new VoltageSource("secondaryVoltageSource");

    private final TransformerInterSystemProcess interSystemProcess =
        new TransformerInterSystemProcess(primaryLoad, secondaryLoad, primaryVoltageSource, secondaryVoltageSource);
    private final Transformer transformer = new Transformer();

    private final TransparentNodeElementInventory inventory = new TransparentNodeElementInventory(4, 64, this);

    private float primaryMaxCurrent = 0;
    private float secondaryMaxCurrent = 0;
    private final TransformerDescriptor transformerDescriptor;

    private boolean populated = false;

    private boolean isIsolator = false;

    public TransformerElement(TransparentNode transparentNode, TransparentNodeDescriptor descriptor) {
        super(transparentNode, descriptor);

        electricalLoadList.add(primaryLoad);
        electricalLoadList.add(secondaryLoad);
        WorldExplosion exp = new WorldExplosion(this).machineExplosion();
        voltagePrimaryWatchdog.set(primaryLoad);
        voltagePrimaryWatchdog.set(exp);
        slowProcessList.add(voltagePrimaryWatchdog);
        voltageSecondaryWatchdog.set(secondaryLoad);
        voltageSecondaryWatchdog.set(exp);
        slowProcessList.add(voltageSecondaryWatchdog);

        transformerDescriptor = (TransformerDescriptor) descriptor;

        slowProcessList.add(new NodePeriodicPublishProcess(node, 1., .5));
    }

    private final VoltageStateWatchDog voltagePrimaryWatchdog = new VoltageStateWatchDog();
    private final VoltageStateWatchDog voltageSecondaryWatchdog = new VoltageStateWatchDog();

    @Override
    public void disconnectJob() {
        super.disconnectJob();
        if (isIsolator)
            Eln.simulator.getMna().removeProcess(interSystemProcess);
    }

    @Override
    public void connectJob() {
        if (isIsolator)
            Eln.simulator.getMna().addProcess(interSystemProcess);
        super.connectJob();
    }

    @Override
    public ElectricalLoad getElectricalLoad(Direction side, LRDU lrdu) {
        if (lrdu != LRDU.Down) return null;
        if (side == front.left()) return primaryLoad;
        if (side == front.right()) return secondaryLoad;
        return null;
    }

    @Override
    public ThermalLoad getThermalLoad(Direction side, LRDU lrdu) {
        return null;
    }

    @Override
    public int getConnectionMask(Direction side, LRDU lrdu) {
        if (lrdu == LRDU.Down) {
            if (side == front.left()) return NodeBase.MASK_ELECTRICAL_POWER;
            if (side == front.right()) return NodeBase.MASK_ELECTRICAL_POWER;
            if (side == front && !grounded) return NodeBase.MASK_ELECTRICAL_POWER;
            if (side == front.back() && !grounded) return NodeBase.MASK_ELECTRICAL_POWER;
        }
        return 0;
    }

    @Override
    public String multiMeterString(Direction side) {
        if (side == front.left())
            return Utils.plotVolt("UP+:", primaryLoad.getU()) + Utils.plotAmpere("IP+:", -primaryLoad.getCurrent());
        if (side == front.right())
            return Utils.plotVolt("US+:", secondaryLoad.getU()) + Utils.plotAmpere("IS+:", -secondaryLoad.getCurrent());

        return Utils.plotVolt("UP+:", primaryLoad.getU()) + Utils.plotAmpere("IP+:", transformer.getACurrentState().getState())
            + Utils.plotVolt("  US+:", secondaryLoad.getU()) + Utils.plotAmpere("IS+:", transformer.getBCurrentState().getState());

    }

    @Override
    public String thermoMeterString(Direction side) {
        return null;
    }

    @Override
    public void initialize() {
        applyIsolation();
        computeInventory();
        connect();
    }

    @Override
    public void connect() {
        if (populated) {
            super.connect();
        } else {
            needPublish();
        }
    }

    @Override
    public void reconnect() {
        if (populated) {
            super.reconnect();
        } else {
            super.disconnect();
            needPublish();
        }
    }

    private void computeInventory() {
        ItemStack primaryCable = inventory.getStackInSlot(TransformerContainer.primaryCableSlotId);
        ItemStack secondaryCable = inventory.getStackInSlot(TransformerContainer.secondaryCableSlotId);
        ItemStack core = inventory.getStackInSlot(TransformerContainer.ferromagneticSlotId);

        GenericCableDescriptor primaryDescriptor = null;
        GenericCableDescriptor secondaryDescriptor = null;

        if (primaryCable != null) {
            Object o = Eln.sixNodeItem.getDescriptor(primaryCable);

            if (o instanceof GenericCableDescriptor) {
                primaryDescriptor = (GenericCableDescriptor) o;
                voltagePrimaryWatchdog.setUNominal(primaryDescriptor.electricalNominalVoltage);
                DP.println(DPType.TRANSPARENT_NODE, "" + primaryDescriptor.electricalNominalVoltage);
            }else{
                voltagePrimaryWatchdog.setUNominal(1000000000);
            }
        } else {
            voltagePrimaryWatchdog.setUNominal(1000000000);
        }

        if (secondaryCable != null) {
            Object o = Eln.sixNodeItem.getDescriptor(secondaryCable);

            if (o instanceof GenericCableDescriptor) {
                secondaryDescriptor = (GenericCableDescriptor) o;
                voltageSecondaryWatchdog.setUNominal(secondaryDescriptor.electricalNominalVoltage);
            }else{
                voltageSecondaryWatchdog.setUNominal(1000000000);
            }
        } else {
            voltageSecondaryWatchdog.setUNominal(1000000000);
        }

        DP.println(DPType.TRANSPARENT_NODE, "voltagePrimary: " + voltagePrimaryWatchdog.getMax());
        DP.println(DPType.TRANSPARENT_NODE, "voltageSecondary: " + voltageSecondaryWatchdog.getMax());

        double coreFactor = 1;
        if (core != null) {
            FerromagneticCoreDescriptor coreDescriptor = (FerromagneticCoreDescriptor) FerromagneticCoreDescriptor.getDescriptor(core);
            coreFactor = coreDescriptor.cableMultiplier;
        }

        if (primaryCable != null && secondaryCable != null && core != null) {
            // everything is ready to go
            primaryDescriptor.applyTo(primaryLoad, coreFactor);
            secondaryDescriptor.applyTo(secondaryLoad, coreFactor);
            primaryMaxCurrent = (float) primaryDescriptor.electricalMaximalCurrent;
            secondaryMaxCurrent = (float) secondaryDescriptor.electricalMaximalCurrent;
            transformer.setRatio(1.0 * secondaryCable.stackSize / primaryCable.stackSize);
            interSystemProcess.setRatio(1.0 * secondaryCable.stackSize / primaryCable.stackSize);
            populated = true;
        } else {
            // missing something
            transformer.setRatio(1);
            interSystemProcess.setRatio(1);
            primaryLoad.highImpedance();
            secondaryLoad.highImpedance();
            if (isIsolator) {
                primaryVoltageSource.setU(0);
                secondaryVoltageSource.setU(0);
            }
            populated = false;
        }
    }

    private void applyIsolation() {
        electricalComponentList.remove(transformer);
        electricalComponentList.remove(primaryVoltageSource);
        electricalComponentList.remove(secondaryVoltageSource);
        primaryLoad.remove(primaryVoltageSource);
        secondaryLoad.remove(secondaryVoltageSource);
        primaryLoad.remove(transformer);
        secondaryLoad.remove(transformer);

        if (isIsolator) {
            primaryVoltageSource.connectTo(primaryLoad, null);
            secondaryVoltageSource.connectTo(secondaryLoad, null);
            electricalComponentList.add(primaryVoltageSource);
            electricalComponentList.add(secondaryVoltageSource);
        } else {
            transformer.connectTo(primaryLoad, secondaryLoad);
            electricalComponentList.add(transformer);
        }
    }

    public void inventoryChange(IInventory inventory) {
        disconnect();
        computeInventory();
        connect();
        needPublish();
    }

    @Override
    public boolean onBlockActivated(EntityPlayer entityPlayer, Direction side, float vx, float vy, float vz) {
        return false;
    }

    @Override
    public boolean hasGui() {
        return true;
    }

    @Override
    public Container newContainer(Direction side, EntityPlayer player) {
        return new TransformerContainer(player, inventory);
    }

    public float getLightOpacity() {
        return 1.0f;
    }

    @Override
    public IInventory getInventory() {
        return inventory;
    }

    @Override
    public void onGroundedChangedByClient() {
        super.onGroundedChangedByClient();
        computeInventory();
        reconnect();
    }

    public static final byte toogleIsIsolator = 0x1;

    @Override
    public byte networkUnserialize(DataInputStream stream) {
        switch (super.networkUnserialize(stream)) {
            case toogleIsIsolator:
                disconnect();
                isIsolator = !isIsolator;
                applyIsolation();
                reconnect();
                needPublish();
                break;
        }
        return 0;
    }

    @Override
    public void networkSerialize(DataOutputStream stream) {
        super.networkSerialize(stream);
        try {
            if (inventory.getStackInSlot(0) == null)
                stream.writeByte(0);
            else
                stream.writeByte(inventory.getStackInSlot(0).stackSize);
            if (inventory.getStackInSlot(1) == null)
                stream.writeByte(0);
            else
                stream.writeByte(inventory.getStackInSlot(1).stackSize);

            Utils.serialiseItemStack(stream, inventory.getStackInSlot(TransformerContainer.ferromagneticSlotId));
            Utils.serialiseItemStack(stream, inventory.getStackInSlot(TransformerContainer.primaryCableSlotId));
            Utils.serialiseItemStack(stream, inventory.getStackInSlot(TransformerContainer.secondaryCableSlotId));

            node.lrduCubeMask.getTranslate(front.down()).serialize(stream);
            stream.writeBoolean(isIsolator);

            float load = 0f;
            if (primaryMaxCurrent != 0 && secondaryMaxCurrent != 0) {
                load = Utils.limit((float) Math.max(primaryLoad.getI() / primaryMaxCurrent,
                    secondaryLoad.getI() / secondaryMaxCurrent), 0f, 1f);
            }
            stream.writeFloat(load);
            stream.writeBoolean(inventory.getStackInSlot(3) != null);

        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("isIsolated", isIsolator);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        isIsolator = nbt.getBoolean("isIsolated");
    }

    @Override
    public Map<String, String> getWaila() {
        Map<String, String> info = new HashMap<String, String>();
        info.put(I18N.tr("Ratio"), Utils.plotValue(transformer.getRatio()));
        info.put(I18N.tr("Isolated"), isIsolator ? I18N.tr("Yes") : I18N.tr("No"));
        if (Eln.wailaEasyMode) {
            FerromagneticCoreDescriptor core =
                (FerromagneticCoreDescriptor) FerromagneticCoreDescriptor.getDescriptor(
                    inventory.getStackInSlot(TransformerContainer.ferromagneticSlotId));
            if (core != null) {
                info.put(I18N.tr("Core factor"), Utils.plotValue(core.cableMultiplier));
            }
            info.put("Voltages", "\u00A7a" + Utils.plotVolt("", primaryLoad.getU()) + " " +
                "\u00A7e" + Utils.plotVolt("", secondaryLoad.getU()));
        }

        try {
            if (isIsolator) {
                int leftSubSystemSize = primaryLoad.getSubSystem().matrixSize();
                int rightSubSystemSize = secondaryLoad.getSubSystem().matrixSize();
                String textColorLeft = "", textColorRight = "";
                if (leftSubSystemSize <= 8) {
                    textColorLeft = "§a";
                } else if (leftSubSystemSize <= 15) {
                    textColorLeft = "§6";
                } else {
                    textColorLeft = "§c";
                }
                if (rightSubSystemSize <= 8) {
                    textColorRight = "§a";
                } else if (rightSubSystemSize <= 15) {
                    textColorRight = "§6";
                } else {
                    textColorRight = "§c";
                }
                info.put(I18N.tr("Subsystem Matrix Size: "), textColorLeft + leftSubSystemSize + " §r| " + textColorRight + rightSubSystemSize + "");
            } else {
                int subSystemSize = transformer.getSubSystem().matrixSize();
                String textColor = "";
                if (subSystemSize <= 8) {
                    textColor = "§a";
                } else if (subSystemSize <= 15) {
                    textColor = "§6";
                } else {
                    textColor = "§c";
                }
                info.put(I18N.tr("Subsystem Matrix Size: "), textColor + subSystemSize);
            }

        } catch (Exception e) {
            if (populated) {
                info.put(I18N.tr("Subsystem Matrix Size: "), "§cNot part of a subsystem!?");
            } else {
                info.put(I18N.tr("Subsystem Matrix Size: "), "Not part of a subsystem");
            }
        }
        return info;
    }

    @Override
    public void readConfigTool(NBTTagCompound compound, EntityPlayer invoker) {
        if(compound.hasKey("isolator")) {
            disconnect();
            isIsolator = compound.getBoolean("isolator");
            applyIsolation();
            reconnect();
            needPublish();
        }
        if(ConfigCopyToolDescriptor.readCableType(compound, "primary", inventory, TransformerContainer.primaryCableSlotId, invoker))
            inventoryChange(inventory);
        if(ConfigCopyToolDescriptor.readCableType(compound, "secondary", inventory, TransformerContainer.secondaryCableSlotId, invoker))
            inventoryChange(inventory);
        if(ConfigCopyToolDescriptor.readGenDescriptor(compound, "core", inventory, TransformerContainer.ferromagneticSlotId, invoker))
            inventoryChange(inventory);
    }

    @Override
    public void writeConfigTool(NBTTagCompound compound, EntityPlayer invoker) {
        compound.setBoolean("isolator", isIsolator);
        ConfigCopyToolDescriptor.writeCableType(compound, "primary", inventory.getStackInSlot(TransformerContainer.primaryCableSlotId));
        ConfigCopyToolDescriptor.writeCableType(compound, "secondary", inventory.getStackInSlot(TransformerContainer.secondaryCableSlotId));
        ConfigCopyToolDescriptor.writeGenDescriptor(compound, "core", inventory.getStackInSlot(TransformerContainer.ferromagneticSlotId));
    }
}
