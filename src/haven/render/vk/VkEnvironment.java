/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven.render.vk;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.util.vma.Vma.*;

/*
 * The Vulkan implementation of haven.render.Environment.
 *
 * It mirrors GLEnvironment's model: Renders are recorded on any
 * thread into self-contained command lists, and process() executes
 * them in submission order on a single render thread. Each process()
 * call becomes one or more queue submissions ("units"); a unit ends
 * at a buffer swap. Resource uploads run in a transfer command
 * buffer that executes before the unit's drawing commands.
 */
public class VkEnvironment implements Environment {
    public static final Config.Variable<Boolean> validate = Config.Variable.propb("haven.vkdebug", false);
    public static final int SLOTS = 2;
    /* Images keep OpenGL's memory layout (row 0 is the bottom of
     * the GL window), which mirrors triangle winding as Vulkan sees
     * it. */
    public static final int FRONT_FACE = VK_FRONT_FACE_CLOCKWISE;
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    public static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    public static final Object DEFCOLOR = new Object() {public String toString() {return("#<vk default color>");}};
    public static final Object DEFDEPTH = new Object() {public String toString() {return("#<vk default depth>");}};

    public final VkInstance inst;
    public final VkPhysicalDevice pdev;
    public final VkDevice dev;
    final VkQueue queue;
    final int qfam;
    final long vma, pipecache, messenger;
    final VkDebugUtilsMessengerCallbackEXT msgcb;
    final VkShaderCompiler compiler;
    public final Caps caps;
    final float linemin, linemax, maxaniso;
    final boolean wideLines, anisotropy, mirrorclamp;
    final int ts_bits;
    final float ts_period;
    final AtomicInteger npipes = new AtomicInteger();
    /* Device objects (buffers, textures, programs) not yet destroyed. */
    final AtomicInteger live = new AtomicInteger();
    final Surface wsys;
    private final Path pipecachefile;
    private Area wnd;

    public interface Surface {
	public String[] extensions();
	public long create(VkInstance inst);
	/* Current drawable size, in pixels. */
	public Coord size();
    }

    public static class VkException extends RuntimeException {
	public final int code;

	public VkException(String call, int code) {
	    super(String.format("%s failed: %s", call, errname(code)));
	    this.code = code;
	}
    }

    static String errname(int code) {
	switch(code) {
	case VK_SUCCESS: return("VK_SUCCESS");
	case VK_NOT_READY: return("VK_NOT_READY");
	case VK_TIMEOUT: return("VK_TIMEOUT");
	case VK_INCOMPLETE: return("VK_INCOMPLETE");
	case VK_ERROR_OUT_OF_HOST_MEMORY: return("VK_ERROR_OUT_OF_HOST_MEMORY");
	case VK_ERROR_OUT_OF_DEVICE_MEMORY: return("VK_ERROR_OUT_OF_DEVICE_MEMORY");
	case VK_ERROR_INITIALIZATION_FAILED: return("VK_ERROR_INITIALIZATION_FAILED");
	case VK_ERROR_DEVICE_LOST: return("VK_ERROR_DEVICE_LOST");
	case VK_ERROR_LAYER_NOT_PRESENT: return("VK_ERROR_LAYER_NOT_PRESENT");
	case VK_ERROR_EXTENSION_NOT_PRESENT: return("VK_ERROR_EXTENSION_NOT_PRESENT");
	case VK_ERROR_FEATURE_NOT_PRESENT: return("VK_ERROR_FEATURE_NOT_PRESENT");
	case VK_ERROR_INCOMPATIBLE_DRIVER: return("VK_ERROR_INCOMPATIBLE_DRIVER");
	case VK_ERROR_FORMAT_NOT_SUPPORTED: return("VK_ERROR_FORMAT_NOT_SUPPORTED");
	case VK_ERROR_SURFACE_LOST_KHR: return("VK_ERROR_SURFACE_LOST_KHR");
	case VK_ERROR_OUT_OF_DATE_KHR: return("VK_ERROR_OUT_OF_DATE_KHR");
	case VK_SUBOPTIMAL_KHR: return("VK_SUBOPTIMAL_KHR");
	default: return("VkResult " + code);
	}
    }

    static void check(int rv, String call) {
	if(rv != VK_SUCCESS)
	    throw(new VkException(call, rv));
    }

    public static class Caps implements Environment.Caps, java.io.Serializable {
	public final String vendor, device, driver;
	public final int apiver;

	Caps(String vendor, String device, String driver, int apiver) {
	    this.vendor = vendor;
	    this.device = device;
	    this.driver = driver;
	    this.apiver = apiver;
	}

	public String vendor() {return(vendor);}
	public String device() {return(device);}
	public String driver() {return(driver);}
    }

    static String vendorname(int id) {
	switch(id) {
	case 0x10de: return("NVIDIA");
	case 0x1002: return("AMD");
	case 0x8086: return("Intel");
	case 0x13b5: return("ARM");
	case 0x5143: return("Qualcomm");
	case 0x106b: return("Apple");
	case 0x10005: return("Mesa");
	default: return(String.format("Vendor %04x", id));
	}
    }

    private static boolean haslayer(MemoryStack st, String name) {
	IntBuffer n = st.mallocInt(1);
	vkEnumerateInstanceLayerProperties(n, null);
	/* On the heap: lists that depend on the driver can be long. */
	VkLayerProperties.Buffer props = VkLayerProperties.malloc(n.get(0));
	try {
	    vkEnumerateInstanceLayerProperties(n, props);
	    for(VkLayerProperties p : props) {
		if(p.layerNameString().equals(name))
		    return(true);
	    }
	    return(false);
	} finally {
	    props.free();
	}
    }

    private static Set<String> devexts(MemoryStack st, VkPhysicalDevice pdev) {
	IntBuffer n = st.mallocInt(1);
	vkEnumerateDeviceExtensionProperties(pdev, (ByteBuffer)null, n, null);
	/* On the heap: drivers list hundreds of extensions (260 bytes
	 * each), more than the stack holds. */
	VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0));
	try {
	    vkEnumerateDeviceExtensionProperties(pdev, (ByteBuffer)null, n, props);
	    Set<String> ret = new HashSet<>();
	    for(VkExtensionProperties p : props)
		ret.add(p.extensionNameString());
	    return(ret);
	} finally {
	    props.free();
	}
    }

    /* Checks, without a window, that some device can run the
     * renderer at all, so that startup can fall back to OpenGL
     * before a window is created. */
    public static void probe() {
	VkInstance inst;
	try(MemoryStack st = stackPush()) {
	    VkApplicationInfo app = VkApplicationInfo.calloc(st).sType$Default()
		.pApplicationName(st.UTF8("Haven & Hearth")).apiVersion(VK_API_VERSION_1_3);
	    VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(st).sType$Default().pApplicationInfo(app);
	    PointerBuffer pp = st.mallocPointer(1);
	    int rv = vkCreateInstance(ici, null, pp);
	    if(rv != VK_SUCCESS)
		throw(new UnavailableException("could not create a Vulkan instance: " + errname(rv)));
	    inst = new VkInstance(pp.get(0), ici);
	} catch(UnsatisfiedLinkError | IllegalStateException e) {
	    throw(new UnavailableException("Vulkan loader not available", e));
	}
	try(MemoryStack st = stackPush()) {
	    IntBuffer n = st.mallocInt(1);
	    check(vkEnumeratePhysicalDevices(inst, n, null), "vkEnumeratePhysicalDevices");
	    PointerBuffer devs = st.mallocPointer(n.get(0));
	    check(vkEnumeratePhysicalDevices(inst, n, devs), "vkEnumeratePhysicalDevices");
	    List<String> rejects = new ArrayList<>();
	    for(int i = 0; i < devs.capacity(); i++) {
		VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), inst);
		VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(st);
		vkGetPhysicalDeviceProperties(pd, props);
		String nm = props.deviceNameString();
		if((VK_API_VERSION_MAJOR(props.apiVersion()) == 1) && (VK_API_VERSION_MINOR(props.apiVersion()) < 3)) {
		    rejects.add(nm + ": Vulkan 1." + VK_API_VERSION_MINOR(props.apiVersion()));
		    continue;
		}
		Set<String> exts = devexts(st, pd);
		if(!exts.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME) || !exts.contains(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) {
		    rejects.add(nm + ": missing swapchain or push-descriptor support");
		    continue;
		}
		VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(st).sType$Default();
		VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(f13.address());
		vkGetPhysicalDeviceFeatures2(pd, f2);
		if(!f13.dynamicRendering()) {
		    rejects.add(nm + ": no dynamic rendering");
		    continue;
		}
		return;
	    }
	    throw(new UnavailableException("no suitable Vulkan device" + (rejects.isEmpty() ? "" : " (" + String.join("; ", rejects) + ")")));
	} finally {
	    vkDestroyInstance(inst, null);
	}
    }

    public boolean presented() {
	return(exec.presented());
    }

    public VkEnvironment(Surface wsys, Area wnd) {
	this.wsys = wsys;
	this.wnd = wnd;
	boolean debug = validate.get();
	VkInstance inst = null;
	long surface = 0;
	try(MemoryStack st = stackPush()) {
	    /* Instance */
	    VkApplicationInfo app = VkApplicationInfo.calloc(st).sType$Default()
		.pApplicationName(st.UTF8("Haven & Hearth")).applicationVersion(1)
		.pEngineName(st.UTF8("haven.render")).engineVersion(1)
		.apiVersion(VK_API_VERSION_1_3);
	    String[] wexts = wsys.extensions();
	    boolean layer = debug && haslayer(st, "VK_LAYER_KHRONOS_validation");
	    PointerBuffer exts = st.mallocPointer(wexts.length + 1 + (layer ? 1 : 0));
	    exts.put(st.UTF8(VK_KHR_SURFACE_EXTENSION_NAME));
	    for(String ext : wexts)
		exts.put(st.UTF8(ext));
	    if(layer)
		exts.put(st.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
	    exts.flip();
	    VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(st).sType$Default()
		.pApplicationInfo(app).ppEnabledExtensionNames(exts);
	    if(layer)
		ici.ppEnabledLayerNames(st.pointers(st.UTF8("VK_LAYER_KHRONOS_validation")));
	    PointerBuffer pp = st.mallocPointer(1);
	    int rv = vkCreateInstance(ici, null, pp);
	    if(rv != VK_SUCCESS)
		throw(new UnavailableException("could not create a Vulkan instance: " + errname(rv)));
	    inst = new VkInstance(pp.get(0), ici);
	} catch(UnsatisfiedLinkError | IllegalStateException e) {
	    throw(new UnavailableException("Vulkan loader not available", e));
	}
	this.inst = inst;
	try {
	    VkDebugUtilsMessengerCallbackEXT msgcb = null;
	    long messenger = 0;
	    if(debug && inst.getCapabilities().VK_EXT_debug_utils) {
		msgcb = VkDebugUtilsMessengerCallbackEXT.create((sev, type, data, user) -> {
			VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
			System.err.println("vulkan: " + d.pMessageString());
			return(VK_FALSE);
		    });
		try(MemoryStack st = stackPush()) {
		    VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(st).sType$Default()
			.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
			.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
			.pfnUserCallback(msgcb);
		    LongBuffer lp = st.mallocLong(1);
		    if(vkCreateDebugUtilsMessengerEXT(inst, mci, null, lp) == VK_SUCCESS)
			messenger = lp.get(0);
		}
	    }
	    this.msgcb = msgcb;
	    this.messenger = messenger;

	    surface = wsys.create(inst);

	    /* Physical device */
	    VkPhysicalDevice best = null;
	    int bestscore = -1, bestfam = -1;
	    List<String> rejects = new ArrayList<>();
	    try(MemoryStack st = stackPush()) {
		IntBuffer n = st.mallocInt(1);
		check(vkEnumeratePhysicalDevices(inst, n, null), "vkEnumeratePhysicalDevices");
		PointerBuffer devs = st.mallocPointer(n.get(0));
		check(vkEnumeratePhysicalDevices(inst, n, devs), "vkEnumeratePhysicalDevices");
		for(int i = 0; i < devs.capacity(); i++) {
		    VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), inst);
		    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(st);
		    vkGetPhysicalDeviceProperties(pd, props);
		    String nm = props.deviceNameString();
		    if(VK_API_VERSION_MAJOR(props.apiVersion()) < 1 || ((VK_API_VERSION_MAJOR(props.apiVersion()) == 1) && (VK_API_VERSION_MINOR(props.apiVersion()) < 3))) {
			rejects.add(nm + ": Vulkan " + VK_API_VERSION_MAJOR(props.apiVersion()) + "." + VK_API_VERSION_MINOR(props.apiVersion()) + " < 1.3");
			continue;
		    }
		    Set<String> exts = devexts(st, pd);
		    if(!exts.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME) || !exts.contains(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) {
			rejects.add(nm + ": missing swapchain or push-descriptor support");
			continue;
		    }
		    VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(st).sType$Default();
		    VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(f13.address());
		    vkGetPhysicalDeviceFeatures2(pd, f2);
		    if(!f13.dynamicRendering()) {
			rejects.add(nm + ": no dynamic rendering");
			continue;
		    }
		    vkGetPhysicalDeviceQueueFamilyProperties(pd, n, null);
		    VkQueueFamilyProperties.Buffer qfs = VkQueueFamilyProperties.malloc(n.get(0));
		    vkGetPhysicalDeviceQueueFamilyProperties(pd, n, qfs);
		    int fam = -1;
		    IntBuffer sup = st.mallocInt(1);
		    for(int q = 0; q < qfs.capacity(); q++) {
			if((qfs.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0)
			    continue;
			vkGetPhysicalDeviceSurfaceSupportKHR(pd, q, surface, sup);
			if(sup.get(0) != 0) {
			    fam = q;
			    break;
			}
		    }
		    qfs.free();
		    if(fam < 0) {
			rejects.add(nm + ": cannot present to the window");
			continue;
		    }
		    int score = 1;
		    if(props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
			score += 1000;
		    else if(props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
			score += 100;
		    if(score > bestscore) {
			best = pd;
			bestscore = score;
			bestfam = fam;
		    }
		}
	    }
	    if(best == null)
		throw(new UnavailableException("no suitable Vulkan device" + (rejects.isEmpty() ? "" : " (" + String.join("; ", rejects) + ")")));
	    this.pdev = best;
	    this.qfam = bestfam;

	    /* Device */
	    try(MemoryStack st = stackPush()) {
		VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(st);
		vkGetPhysicalDeviceProperties(pdev, props);
		VkPhysicalDeviceDriverProperties drv = VkPhysicalDeviceDriverProperties.calloc(st).sType$Default();
		VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(st).sType$Default().pNext(drv.address());
		vkGetPhysicalDeviceProperties2(pdev, p2);
		String drvstr = drv.driverNameString() + " " + drv.driverInfoString();
		this.caps = new Caps(vendorname(props.vendorID()), props.deviceNameString(),
				     String.format("Vulkan %d.%d.%d (%s)", VK_API_VERSION_MAJOR(props.apiVersion()), VK_API_VERSION_MINOR(props.apiVersion()),
						   VK_API_VERSION_PATCH(props.apiVersion()), drvstr.trim()),
				     props.apiVersion());
		VkPhysicalDeviceLimits lim = props.limits();
		this.linemin = lim.lineWidthRange(0);
		this.linemax = lim.lineWidthRange(1);
		this.maxaniso = lim.maxSamplerAnisotropy();
		this.ts_period = lim.timestampPeriod();

		VkQueueFamilyProperties.Buffer qfs;
		{
		    IntBuffer n = st.mallocInt(1);
		    vkGetPhysicalDeviceQueueFamilyProperties(pdev, n, null);
		    qfs = VkQueueFamilyProperties.malloc(n.get(0));
		    vkGetPhysicalDeviceQueueFamilyProperties(pdev, n, qfs);
		}
		this.ts_bits = qfs.get(qfam).timestampValidBits();
		qfs.free();

		VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(st).sType$Default();
		VkPhysicalDeviceVulkan13Features a13 = VkPhysicalDeviceVulkan13Features.calloc(st).sType$Default().pNext(a12.address());
		VkPhysicalDeviceFeatures2 af = VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(a13.address());
		vkGetPhysicalDeviceFeatures2(pdev, af);
		VkPhysicalDeviceFeatures have = af.features();
		this.wideLines = have.wideLines();
		this.anisotropy = have.samplerAnisotropy();
		this.mirrorclamp = a12.samplerMirrorClampToEdge();

		VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(st).sType$Default()
		    .samplerMirrorClampToEdge(mirrorclamp);
		VkPhysicalDeviceVulkan13Features e13 = VkPhysicalDeviceVulkan13Features.calloc(st).sType$Default().pNext(e12.address())
		    .dynamicRendering(true);
		VkPhysicalDeviceFeatures2 ef = VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(e13.address());
		ef.features().wideLines(wideLines).samplerAnisotropy(anisotropy)
		    .largePoints(have.largePoints()).independentBlend(have.independentBlend())
		    .fillModeNonSolid(have.fillModeNonSolid());
		VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, st);
		qci.get(0).sType$Default().queueFamilyIndex(qfam).pQueuePriorities(st.floats(1.0f));
		VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(st).sType$Default().pNext(ef.address())
		    .pQueueCreateInfos(qci)
		    .ppEnabledExtensionNames(st.pointers(st.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), st.UTF8(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)));
		PointerBuffer pp = st.mallocPointer(1);
		int rv = vkCreateDevice(pdev, dci, null, pp);
		if(rv != VK_SUCCESS)
		    throw(new UnavailableException("could not create Vulkan device: " + errname(rv)));
		this.dev = new VkDevice(pp.get(0), pdev, dci, VK_API_VERSION_1_3);
		vkGetDeviceQueue(dev, qfam, 0, pp);
		this.queue = new VkQueue(pp.get(0), dev);

		VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(st).set(inst, dev);
		VmaAllocatorCreateInfo aci = VmaAllocatorCreateInfo.calloc(st)
		    .physicalDevice(pdev).device(dev).instance(inst).pVulkanFunctions(fns)
		    .vulkanApiVersion(VK_API_VERSION_1_3);
		check(vmaCreateAllocator(aci, pp), "vmaCreateAllocator");
		this.vma = pp.get(0);
	    }

	    this.compiler = new VkShaderCompiler();
	    Path pcf = (compiler.cachedir() == null) ? null : compiler.cachedir().resolve("pipelines.bin");
	    this.pipecachefile = pcf;
	    ByteBuffer init = null;
	    if(pcf != null) {
		try {
		    byte[] data = Files.readAllBytes(pcf);
		    init = MemoryUtil.memAlloc(data.length);
		    init.put(data).flip();
		} catch(java.io.IOException e) {
		    init = null;
		}
	    }
	    try(MemoryStack st = stackPush()) {
		VkPipelineCacheCreateInfo pci = VkPipelineCacheCreateInfo.calloc(st).sType$Default();
		if(init != null)
		    pci.pInitialData(init);
		LongBuffer lp = st.mallocLong(1);
		int rv = vkCreatePipelineCache(dev, pci, null, lp);
		if((rv != VK_SUCCESS) && (init != null)) {
		    pci.pInitialData(null);
		    rv = vkCreatePipelineCache(dev, pci, null, lp);
		}
		check(rv, "vkCreatePipelineCache");
		this.pipecache = lp.get(0);
	    } finally {
		if(init != null)
		    MemoryUtil.memFree(init);
	    }
	    this.exec = new VkExec(this, surface);
	    surface = 0;
	} catch(RuntimeException e) {
	    if(surface != 0)
		vkDestroySurfaceKHR(inst, surface, null);
	    vkDestroyInstance(inst, null);
	    throw(e);
	}
    }

    final VkExec exec;

    /* Format support */

    private final Map<Integer, VkFormatProperties> fmtprops = new HashMap<>();
    private VkFormatProperties fmtprops(int fmt) {
	synchronized(fmtprops) {
	    VkFormatProperties ret = fmtprops.get(fmt);
	    if(ret == null) {
		ret = VkFormatProperties.calloc();
		vkGetPhysicalDeviceFormatProperties(pdev, fmt, ret);
		fmtprops.put(fmt, ret);
	    }
	    return(ret);
	}
    }

    public boolean vtxsupported(int fmt) {
	return((fmtprops(fmt).bufferFeatures() & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0);
    }

    public boolean attachable(int fmt, boolean depth) {
	int bit = depth ? VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
	return((fmtprops(fmt).optimalTilingFeatures() & bit) != 0);
    }

    public boolean linearfilter(int fmt) {
	return((fmtprops(fmt).optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0);
    }

    /* Environment interface */

    public VkRender render() {
	return(new VkRender(this));
    }

    public VkDrawList drawlist() {
	return(new VkDrawList(this));
    }

    public FillBuffer fillbuf(DataBuffer tgt, int from, int to) {
	return(new VkFillBuffer(to - from));
    }

    public Caps caps() {return(caps);}

    public void reshape(Area wnd) {this.wnd = wnd;}
    public Area shape() {return(wnd);}

    private final Queue<VkRender> submitted = new LinkedList<>();
    private boolean invalid = false;

    public void submit(Render cmd) {
	if(!(cmd instanceof VkRender))
	    throw(new IllegalArgumentException("environment mismatch"));
	VkRender vcmd = (VkRender)cmd;
	if(vcmd.env != this)
	    throw(new IllegalArgumentException("environment mismatch"));
	boolean inv;
	synchronized(submitted) {
	    inv = invalid;
	    if(!inv && !vcmd.empty()) {
		submitted.add(vcmd);
		submitted.notifyAll();
		return;
	    }
	}
	if(inv)
	    vcmd.abort();
	vcmd.dispose();
    }

    public boolean submitwait(long timeout) throws InterruptedException {
	synchronized(submitted) {
	    long end = System.currentTimeMillis() + timeout;
	    while(submitted.peek() == null) {
		long now = System.currentTimeMillis();
		if(now >= end)
		    return(false);
		submitted.wait(end - now);
	    }
	    return(true);
	}
    }

    /* Recording arenas, reused between renders. Pooled buffers are
     * not zeroed; packing writes every byte a draw reads. */
    private final List<ByteBuffer> arenas = new ArrayList<>();

    ByteBuffer getarena(int min) {
	synchronized(arenas) {
	    for(int i = 0; i < arenas.size(); i++) {
		if(arenas.get(i).capacity() >= min) {
		    ByteBuffer ret = arenas.remove(i);
		    ret.clear();
		    return(ret);
		}
	    }
	}
	return(Utils.mkbbuf(Math.max(min, 65536)));
    }

    void putarena(ByteBuffer buf) {
	synchronized(arenas) {
	    if(arenas.size() < 16)
		arenas.add(buf);
	}
    }

    /* Resource preparation. Prep items run in the transfer
     * command buffer of the next unit, before any render submitted
     * after the resource was prepared. */

    private List<Consumer<VkExec>> prep = new ArrayList<>();
    private final Object prepmon = new Object();

    void prep(Consumer<VkExec> item) {
	synchronized(prepmon) {
	    prep.add(item);
	}
    }

    public void process() {
	List<VkRender> copy;
	List<Consumer<VkExec>> prep;
	synchronized(submitted) {
	    copy = new ArrayList<>(submitted);
	    submitted.clear();
	}
	synchronized(prepmon) {
	    prep = this.prep;
	    this.prep = new ArrayList<>();
	}
	synchronized(exec) {
	    exec.process(prep, copy, wsys.size());
	}
	clean();
    }

    public void dispose() {
	builders.shutdownNow();
	Collection<VkRender> copy;
	synchronized(submitted) {
	    copy = new ArrayList<>(submitted);
	    submitted.clear();
	    invalid = true;
	}
	for(VkRender cmd : copy) {
	    cmd.abort();
	    cmd.dispose();
	}
	synchronized(exec) {
	    exec.dispose();
	}
	savepipecache();
	synchronized(pmon) {
	    for(SavedProg s : ptab) {
		for(; s != null; s = s.next)
		    s.prog.destroy0();
	    }
	}
	synchronized(samplers) {
	    for(Long smp : samplers.values())
		vkDestroySampler(dev, smp, null);
	    samplers.clear();
	}
	vkDestroyPipelineCache(dev, pipecache, null);
	compiler.dispose();
	/* Resources the client still holds (it normally disposes the
	 * window only when exiting) keep the device alive; tearing it
	 * down under them would crash. */
	if(live.get() > 0)
	    return;
	vmaDestroyAllocator(vma);
	vkDestroyDevice(dev, null);
	if(messenger != 0)
	    vkDestroyDebugUtilsMessengerEXT(inst, messenger, null);
	vkDestroyInstance(inst, null);
	if(msgcb != null)
	    msgcb.free();
    }

    private void savepipecache() {
	if(pipecachefile == null)
	    return;
	try(MemoryStack st = stackPush()) {
	    PointerBuffer sz = st.mallocPointer(1);
	    if(vkGetPipelineCacheData(dev, pipecache, sz, null) != VK_SUCCESS)
		return;
	    ByteBuffer data = MemoryUtil.memAlloc((int)sz.get(0));
	    try {
		if(vkGetPipelineCacheData(dev, pipecache, sz, data) != VK_SUCCESS)
		    return;
		byte[] buf = new byte[(int)sz.get(0)];
		data.get(buf);
		Files.write(pipecachefile, buf);
	    } catch(java.io.IOException e) {
	    } finally {
		MemoryUtil.memFree(data);
	    }
	}
    }

    /* Callbacks */

    final Queue<Runnable> callbacks = new LinkedList<>();
    private Thread cbthread = null;

    private void ckcbt() {
	synchronized(callbacks) {
	    if(!callbacks.isEmpty() && (cbthread == null)) {
		cbthread = new HackThread(this::cbloop, "Render-query callback thread");
		cbthread.setDaemon(true);
		cbthread.start();
	    }
	}
    }

    private void cbloop() {
	try {
	    double last = Utils.rtime(), now = last;
	    while(true) {
		Runnable cb;
		synchronized(callbacks) {
		    while(callbacks.isEmpty()) {
			if(now - last >= 5) {
			    cbthread = null;
			    return;
			}
			callbacks.wait((int)((last + 6 - now) * 1000));
			now = Utils.rtime();
		    }
		    cb = callbacks.remove();
		    last = now;
		}
		cb.run();
	    }
	} catch(InterruptedException e) {
	} finally {
	    synchronized(callbacks) {
		if(cbthread == Thread.currentThread())
		    cbthread = null;
		ckcbt();
	    }
	}
    }

    void callback(Runnable cb) {
	synchronized(callbacks) {
	    callbacks.add(cb);
	    callbacks.notifyAll();
	    ckcbt();
	}
    }

    /* Disposal sequencing (as in GLEnvironment) */

    private final Object seqmon = new Object();
    private boolean[] sequse = new boolean[16];
    private int seqhead = 1, seqtail = seqhead;
    final Collection<VkObject> disposed = new LinkedList<>();

    private void seqresize(int nsz) {
	boolean[] cseq = sequse, nseq = new boolean[nsz];
	int csz = cseq.length;
	for(int i = 0; i < csz; i++)
	    nseq[(seqtail + i) & (nsz - 1)] = cseq[(seqtail + i) & (csz - 1)];
	sequse = nseq;
	if(nsz >= 0x4000)
	    Warning.warn("warning: dispose queue size increased to " + nsz);
    }

    int seqreg() {
	synchronized(seqmon) {
	    int seq = seqhead;
	    if(++seqhead == 0)
		seqhead = 1;
	    if(seqhead - seqtail == sequse.length - 1)
		seqresize(sequse.length << 1);
	    sequse[seq & (sequse.length - 1)] = true;
	    return(seq);
	}
    }

    void sequnreg(int seq) {
	if(seq == 0)
	    return;
	synchronized(seqmon) {
	    int m = sequse.length - 1;
	    int si = seq & m;
	    if(!sequse[si])
		throw(new AssertionError());
	    sequse[si] = false;
	    if(seq == seqtail) {
		while((seqhead - seqtail > 0) && !sequse[seqtail & m])
		    seqtail++;
	    }
	}
    }

    class Sequence implements Disposable {
	public final int no;
	private final Runnable clean;
	private final String desc;
	private volatile boolean cleaned = false;

	Sequence(Object owner) {
	    this.desc = owner.toString();
	    this.no = seqreg();
	    clean = Finalizer.finalize(owner, this::disposed);
	}

	private void disposed() {
	    sequnreg(no);
	    if(!cleaned)
		Warning.warn("warning: disposal sequence leaked: " + desc);
	}

	public void dispose() {
	    cleaned = true;
	    clean.run();
	}
    }

    void disposed(VkObject obj) {
	synchronized(disposed) {
	    synchronized(seqmon) {
		obj.dispseq = seqhead;
	    }
	    disposed.add(obj);
	}
    }

    /* Objects that no unprocessed render can reference anymore. */
    List<VkObject> reapable() {
	int tail;
	synchronized(seqmon) {
	    tail = seqtail;
	}
	List<VkObject> ret = new ArrayList<>();
	synchronized(disposed) {
	    for(Iterator<VkObject> i = disposed.iterator(); i.hasNext();) {
		VkObject obj = i.next();
		if(obj.dispseq - tail > 0)
		    break;
		ret.add(obj);
		i.remove();
	    }
	}
	return(ret);
    }

    /* Buffers */

    VkBuf prepare(VertexArray.Buffer buf) {
	synchronized(buf) {
	    if(buf.usage == DataBuffer.Usage.EPHEMERAL)
		throw(new IllegalArgumentException("ephemeral buffers have no device object"));
	    VkBuf ret = VkReference.get(buf.ro, VkBuf.class);
	    if((ret == null) || (ret.env != this)) {
		if(buf.ro != null)
		    buf.ro.dispose();
		ret = new VkBuf(this, buf.size(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
		buf.ro = new VkReference<>(ret);
		if(buf.init != null) {
		    FillBuffer data = buf.init.fill(buf, this);
		    ret.upload(0, data);
		}
	    }
	    return(ret);
	}
    }

    VkBuf prepare(Model.Indices buf) {
	synchronized(buf) {
	    if(buf.usage == DataBuffer.Usage.EPHEMERAL)
		throw(new IllegalArgumentException("ephemeral buffers have no device object"));
	    VkBuf ret = VkReference.get(buf.ro, VkBuf.class);
	    if((ret == null) || (ret.env != this)) {
		if(buf.ro != null)
		    buf.ro.dispose();
		boolean wide = (buf.fmt == NumberFormat.UINT8);
		ret = new VkBuf(this, wide ? (buf.n * 2) : buf.size(), VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
		ret.widen8 = wide;
		buf.ro = new VkReference<>(ret);
		if(buf.init != null) {
		    FillBuffer data = buf.init.fill(buf, this);
		    ret.upload(0, data);
		}
	    }
	    return(ret);
	}
    }

    /* Textures */

    VkTexture prepare(Texture tex) {
	synchronized(tex) {
	    VkTexture ret = VkReference.get(tex.ro, VkTexture.class);
	    if((ret == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		ret = VkTexture.create(this, tex);
		tex.ro = new VkReference<>(ret);
	    }
	    return(ret);
	}
    }

    /* Samplers */

    public static class TexBind {
	public final VkTexture tex;
	public final long sampler;

	public TexBind(VkTexture tex, long sampler) {
	    this.tex = tex;
	    this.sampler = sampler;
	}

	public boolean equals(Object o) {
	    if(!(o instanceof TexBind))
		return(false);
	    TexBind that = (TexBind)o;
	    return((this.tex == that.tex) && (this.sampler == that.sampler));
	}

	public int hashCode() {
	    return((System.identityHashCode(tex) * 31) + Long.hashCode(sampler));
	}
    }

    private final Map<List<Object>, Long> samplers = new HashMap<>();

    private static int vkfilter(Texture.Filter f) {
	return((f == Texture.Filter.NEAREST) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
    }

    private int vkwrap(Texture.Wrapping w) {
	switch(w) {
	case REPEAT: return(VK_SAMPLER_ADDRESS_MODE_REPEAT);
	case REPEAT_MIRROR: return(VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT);
	case CLAMP: return(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
	case CLAMP_BORDER: return(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);
	case CLAMP_MIRROR: return(mirrorclamp ? VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
	default: throw(new IllegalArgumentException(String.valueOf(w)));
	}
    }

    private static int vkborder(FColor c, boolean integer) {
	boolean white = (c.r + c.g + c.b) > 1.5f;
	boolean opaque = c.a > 0.5f;
	if(integer)
	    return(white ? VK_BORDER_COLOR_INT_OPAQUE_WHITE : (opaque ? VK_BORDER_COLOR_INT_OPAQUE_BLACK : VK_BORDER_COLOR_INT_TRANSPARENT_BLACK));
	return(white ? VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE : (opaque ? VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK));
    }

    long sampler(Texture.Sampler<?> smp, VkTexture tex) {
	boolean nearest = tex.fmt.integer || !linearfilter(tex.fmt.vk);
	Texture.Filter mag = nearest ? Texture.Filter.NEAREST : smp.magfilter;
	Texture.Filter min = nearest ? Texture.Filter.NEAREST : smp.minfilter;
	Texture.Filter mip = nearest ? ((smp.mipfilter == null) ? null : Texture.Filter.NEAREST) : smp.mipfilter;
	float aniso = (anisotropy && !nearest) ? Math.min(smp.effanisotropy(), maxaniso) : 0;
	int border = vkborder(smp.border, tex.fmt.integer);
	List<Object> key = Arrays.asList(mag, min, mip, smp.swrap, smp.twrap, smp.rwrap, aniso, border);
	synchronized(samplers) {
	    Long ret = samplers.get(key);
	    if(ret == null) {
		try(MemoryStack st = stackPush()) {
		    VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(st).sType$Default()
			.magFilter(vkfilter(mag)).minFilter(vkfilter(min))
			.mipmapMode(((mip == null) || (mip == Texture.Filter.NEAREST)) ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR)
			.addressModeU(vkwrap(smp.swrap)).addressModeV(vkwrap(smp.twrap)).addressModeW(vkwrap(smp.rwrap))
			.mipLodBias(0).anisotropyEnable(aniso > 1).maxAnisotropy(Math.max(aniso, 1))
			.compareEnable(false).minLod(0).maxLod((mip == null) ? 0 : VK_LOD_CLAMP_NONE)
			.borderColor(border).unnormalizedCoordinates(false);
		    LongBuffer lp = st.mallocLong(1);
		    check(vkCreateSampler(dev, ci, null, lp), "vkCreateSampler");
		    samplers.put(key, ret = lp.get(0));
		}
	    }
	    return(ret);
	}
    }

    Object prepuval(Object val) {
	if(val instanceof Texture.Sampler) {
	    Texture.Sampler<?> smp = (Texture.Sampler<?>)val;
	    VkTexture tex = prepare(smp.tex);
	    return(new TexBind(tex, sampler(smp, tex)));
	}
	if(val instanceof Object[]) {
	    Object[] a = (Object[])val;
	    if((a.length > 0) && (a[0] instanceof Texture.Sampler)) {
		TexBind[] ret = new TexBind[a.length];
		for(int i = 0; i < a.length; i++)
		    ret[i] = (a[i] == null) ? null : (TexBind)prepuval(a[i]);
		return(ret);
	    }
	}
	return(val);
    }

    /* Render targets */

    public static class Attach {
	public final VkTexture tex;
	public final int level;

	Attach(VkTexture tex, int level) {
	    this.tex = tex;
	    this.level = level;
	}

	public boolean equals(Object o) {
	    if(!(o instanceof Attach))
		return(false);
	    Attach that = (Attach)o;
	    return((this.tex == that.tex) && (this.level == that.level));
	}

	public int hashCode() {
	    return((System.identityHashCode(tex) * 31) + level);
	}
    }

    Object prepfval(Object val) {
	if(val == null)
	    return(null);
	if(val == FragColor.defcolor)
	    return(DEFCOLOR);
	if(val == DepthBuffer.defdepth)
	    return(DEFDEPTH);
	if(val instanceof Texture.Image) {
	    Texture.Image<?> img = (Texture.Image<?>)val;
	    if(!(img.tex instanceof Texture2D))
		throw(new IllegalArgumentException("Unsupported image type for framebuffer attachment: " + img));
	    VkTexture tex = prepare(img.tex);
	    tex.attachable();
	    return(new Attach(tex, img.level));
	}
	throw(new IllegalArgumentException("Illegal framebuffer attachment: " + val));
    }

    /* Programs (as in GLEnvironment) */

    static class SavedProg {
	final int hash;
	final ShaderMacro[] shaders;
	final VkProgram prog;
	SavedProg next;
	boolean used = true;

	SavedProg(int hash, ShaderMacro[] shaders, VkProgram prog) {
	    this.hash = hash;
	    this.shaders = Arrays.copyOf(shaders, shaders.length);
	    this.prog = prog;
	}
    }

    private final Object pmon = new Object();
    private SavedProg[] ptab = new SavedProg[32];
    private int nprog = 0;

    private SavedProg findprog(int hash, ShaderMacro[] shaders) {
	int idx = hash & (ptab.length - 1);
	outer: for(SavedProg s = ptab[idx]; s != null; s = s.next) {
	    if(s.hash != hash)
		continue;
	    ShaderMacro[] a, b;
	    if(shaders.length < s.shaders.length) {
		a = shaders; b = s.shaders;
	    } else {
		a = s.shaders; b = shaders;
	    }
	    int i = 0;
	    for(; i < a.length; i++) {
		if(a[i] != b[i])
		    continue outer;
	    }
	    for(; i < b.length; i++) {
		if(b[i] != null)
		    continue outer;
	    }
	    return(s);
	}
	return(null);
    }

    private void rehash(int nlen) {
	SavedProg[] ntab = new SavedProg[nlen];
	for(int i = 0; i < ptab.length; i++) {
	    while(ptab[i] != null) {
		SavedProg s = ptab[i];
		ptab[i] = s.next;
		int ni = s.hash & (nlen - 1);
		s.next = ntab[ni];
		ntab[ni] = s;
	    }
	}
	ptab = ntab;
    }

    /* Programs and pipelines for the draw lists are built on these
     * threads, so that a new kind of object appearing does not stall
     * the frame while its shaders compile; it is drawn once ready. */
    final java.util.concurrent.ExecutorService builders = java.util.concurrent.Executors.newFixedThreadPool(
	Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2)), r -> {
	    Thread t = new HackThread(r, "Vulkan shader compiler");
	    t.setDaemon(true);
	    return(t);
	});

    private static class PKey {
	final int hash;
	final ShaderMacro[] shaders;
	PKey(int hash, ShaderMacro[] shaders) {this.hash = hash; this.shaders = shaders;}
	public int hashCode() {return(hash);}
	public boolean equals(Object o) {
	    return((o instanceof PKey) && (((PKey)o).hash == hash) && Arrays.equals(((PKey)o).shaders, shaders));
	}
    }
    private final Map<PKey, java.util.concurrent.Future<VkProgram>> pendprog = new HashMap<>();

    /* Like getprog, but a program not built yet is built in the
     * background: null until it is ready. */
    public VkProgram getprogasync(int hash, ShaderMacro[] shaders) {
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		s.used = true;
		return(s.prog);
	    }
	}
	PKey key = new PKey(hash, shaders.clone());
	java.util.concurrent.Future<VkProgram> f;
	synchronized(pendprog) {
	    f = pendprog.get(key);
	    if(f == null) {
		pendprog.put(key, builders.submit(() -> {
			    VkProgram prog = getprog(key.hash, key.shaders);
			    /* Saved in the program table by now, where the
			     * next ask finds it; so the entry need not wait to
			     * be claimed (an object that has left view never
			     * would). A failed build keeps its entry, so that
			     * the error reaches the caller. */
			    synchronized(pendprog) {
				pendprog.remove(key);
			    }
			    return(prog);
			}));
		return(null);
	    }
	    if(!f.isDone())
		return(null);
	    pendprog.remove(key);
	}
	try {
	    return(f.get());
	} catch(java.util.concurrent.ExecutionException e) {
	    Throwable c = e.getCause();
	    if(c instanceof RuntimeException)
		throw((RuntimeException)c);
	    if(c instanceof Error)
		throw((Error)c);
	    throw(new RuntimeException(c));
	} catch(InterruptedException e) {
	    /* Not reached: the future is done. */
	    Thread.currentThread().interrupt();
	    return(null);
	}
    }

    public VkProgram getprog(int hash, ShaderMacro[] shaders) {
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		s.used = true;
		return(s.prog);
	    }
	}
	Collection<ShaderMacro> mods = new LinkedList<>();
	for(int i = 0; i < shaders.length; i++) {
	    if(shaders[i] != null)
		mods.add(shaders[i]);
	}
	VkProgram prog = VkProgram.build(this, mods);
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		prog.dispose();
		s.used = true;
		return(s.prog);
	    }
	    int idx = hash & (ptab.length - 1);
	    SavedProg save = new SavedProg(hash, shaders, prog);
	    save.next = ptab[idx];
	    ptab[idx] = save;
	    nprog++;
	    if(nprog > ptab.length)
		rehash(ptab.length * 2);
	    return(prog);
	}
    }

    private void cleanprogs() {
	synchronized(pmon) {
	    for(int i = 0; i < ptab.length; i++) {
		SavedProg c, p;
		for(c = ptab[i], p = null; c != null; c = c.next) {
		    int rc = c.prog.locked.get();
		    if(c.used || (rc > 0)) {
			if(rc < 1)
			    c.used = false;
			p = c;
		    } else {
			if(p == null)
			    ptab[i] = c.next;
			else
			    p.next = c.next;
			c.prog.dispose();
			nprog--;
		    }
		}
	    }
	}
    }

    private double lastpclean = Utils.rtime(), lastpsave = Utils.rtime();
    private void clean() {
	double now = Utils.rtime();
	if(now - lastpclean > 60) {
	    cleanprogs();
	    lastpclean = now;
	}
	if(now - lastpsave > 300) {
	    savepipecache();
	    lastpsave = now;
	}
    }

    public int numprogs() {return(nprog);}

    /* compatible() */

    public boolean compatible(DrawList ob) {
	return((ob instanceof VkDrawList) && (((VkDrawList)ob).env == this));
    }

    public boolean compatible(Texture ob) {
	VkTexture ro = VkReference.get(ob.ro, VkTexture.class);
	return((ro != null) && (ro.env == this));
    }

    public boolean compatible(DataBuffer ob) {
	Object ro;
	if(ob instanceof Model.Indices)
	    ro = ((Model.Indices)ob).ro;
	else if(ob instanceof VertexArray.Buffer)
	    ro = ((VertexArray.Buffer)ob).ro;
	else
	    throw(new IllegalArgumentException(String.valueOf(ob)));
	VkBuf buf = VkReference.get(ro, VkBuf.class);
	return((buf != null) && (buf.env == this));
    }

    /* Called by the toolkit on the AWT thread, around the life of the
     * native window. */
    public void surfacegone() {
	synchronized(exec) {
	    exec.surfacegone();
	}
    }

    public void surfaceback() {
	long surface = wsys.create(inst);
	synchronized(exec) {
	    exec.setsurface(surface);
	}
    }

    public String stats() {
	return(String.format("Vulkan: progs %d, pipelines %d, %s", nprog, npipes.get(), exec.stats()));
    }
}
