package nurgling.tools;

import haven.*;
import haven.ModSprite.*;
import haven.render.NodeWrap;
import haven.res.lib.vmat.VarWrap;

import java.util.*;

public class NurglingVarMatOverride implements Mod {
    private final FastMesh.MeshRes[] meshes;

    public NurglingVarMatOverride(FastMesh.MeshRes[] meshes) {
	this.meshes = meshes;
    }

    public void operate(Cons cons) {
	Gob gob = cons.spr().gob;
	if (gob == null || gob.ngob == null) return;

	String resName = cons.spr().res != null ? cons.spr().res.name : null;
	if (resName == null) return;

	int matFlags = gob.ngob.customMask ? gob.ngob.mask() : cons.spr().flags;
	MaterialFactory.Status status = MaterialFactory.getStatus(resName, matFlags);
	if (status == MaterialFactory.Status.NOTDEFINED) return;

	// Build vm_slot_id -> custom Material mapping
	Map<Integer, Material> vmCustomMats = new HashMap<>();
	for (FastMesh.MeshRes mr : meshes) {
	    if (mr.mat == null) continue;
	    String sid = mr.rdat.get("vm");
	    int vmId = (sid == null) ? -1 : Integer.parseInt(sid);
	    if (vmId >= 0) {
		Material baseMat = mr.mat.get();
		Map<Integer, Material> customMats = MaterialFactory.getMaterials(resName, status, baseMat);
		if (customMats != null) {
		    Material customMat = customMats.get(mr.mat.id);
		    if (customMat != null) {
			vmCustomMats.put(vmId, customMat);
		    }
		}
	    }
	}

	if (vmCustomMats.isEmpty()) return;

	// Replace VarWrap.Applier wraps with custom materials
	for (Part part : cons.parts) {
	    ListIterator<NodeWrap> it = part.wraps.listIterator();
	    while (it.hasNext()) {
		NodeWrap wrap = it.next();
		if (wrap instanceof VarWrap.Applier) {
		    VarWrap.Applier vwa = (VarWrap.Applier) wrap;
		    Material customMat = vmCustomMats.get(vwa.mid);
		    if (customMat != null) {
			it.set(new VarWrap.Applier(customMat, vwa.mid));
		    }
		}
	    }
	}
    }

    public int order() { return 150; }
}
