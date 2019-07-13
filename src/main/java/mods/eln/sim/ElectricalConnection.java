package mods.eln.sim;

import mods.eln.Eln;
import mods.eln.debug.DebugType;
import mods.eln.sim.mna.component.InterSystem;

public class ElectricalConnection extends InterSystem {

    ElectricalLoad L1, L2;

    public ElectricalConnection(ElectricalLoad L1, ElectricalLoad L2) {
        this.L1 = L1;
        this.L2 = L2;
        if(L1 == L2) Eln.dp.println(DebugType.MNA,"WARNING: Attempt to connect load to itself?");
    }

    public void notifyRsChange() {
        double R = ((ElectricalLoad) getAPin()).getRs() + ((ElectricalLoad) getBPin()).getRs();
        setR(R);
    }

    @Override
    public void onAddToRootSystem() {
        this.connectTo(L1, L2);
    /*	((ElectricalLoad) aPin).electricalConnections.add(this);
		((ElectricalLoad) bPin).electricalConnections.add(this);*/
        notifyRsChange();
    }

    @Override
    public void onRemovefromRootSystem() {
        this.breakConnection();
	/*	((ElectricalLoad) aPin).electricalConnections.remove(this);
		((ElectricalLoad) bPin).electricalConnections.remove(this);*/
    }
}
