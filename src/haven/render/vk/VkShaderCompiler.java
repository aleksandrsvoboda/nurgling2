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

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import haven.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/* Compiles GLSL to SPIR-V with shaderc. Shaders are generated at
 * runtime from (possibly server-supplied) shader macros, so they
 * cannot be precompiled; results are cached on disk by source
 * hash instead. */
public class VkShaderCompiler implements Disposable {
    public static final int VERTEX = 0, FRAGMENT = 1;
    private final long compiler, options;
    private final Path cachedir;

    public static class CompileException extends RuntimeException {
	public final String source, log;

	public CompileException(String msg, String source, String log) {
	    super(msg + "\n" + log);
	    this.source = source;
	    this.log = log;
	}
    }

    public VkShaderCompiler() {
	compiler = shaderc_compiler_initialize();
	if(compiler == 0)
	    throw(new RuntimeException("could not initialize shaderc"));
	options = shaderc_compile_options_initialize();
	shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
	shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
	Path dir = null;
	try {
	    Path base = Config.localdir();
	    if(base != null) {
		dir = base.resolve("vkcache");
		Files.createDirectories(dir);
	    }
	} catch(IOException | RuntimeException e) {
	    dir = null;
	}
	this.cachedir = dir;
    }

    private static String hash(int kind, String src) {
	try {
	    MessageDigest dig = MessageDigest.getInstance("SHA-256");
	    dig.update((byte)kind);
	    dig.update(src.getBytes(Utils.utf8));
	    StringBuilder buf = new StringBuilder();
	    for(byte b : dig.digest())
		buf.append(String.format("%02x", b & 0xff));
	    return(buf.toString());
	} catch(NoSuchAlgorithmException e) {
	    throw(new AssertionError(e));
	}
    }

    public byte[] compile(int kind, String src) {
	String id = null;
	if(cachedir != null) {
	    id = hash(kind, src);
	    try {
		return(Files.readAllBytes(cachedir.resolve(id + ".spv")));
	    } catch(IOException e) {
	    }
	}
	byte[] ret;
	synchronized(this) {
	    long res = shaderc_compile_into_spv(compiler, src, (kind == VERTEX) ? shaderc_vertex_shader : shaderc_fragment_shader,
						 (kind == VERTEX) ? "vertex" : "fragment", "main", options);
	    if(res == 0)
		throw(new RuntimeException("shaderc returned no result"));
	    try {
		if(shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success)
		    throw(new CompileException("Failed to compile " + ((kind == VERTEX) ? "vertex" : "fragment") + " shader",
					       src, shaderc_result_get_error_message(res)));
		ByteBuffer spv = shaderc_result_get_bytes(res);
		ret = new byte[spv.remaining()];
		spv.get(ret);
	    } finally {
		shaderc_result_release(res);
	    }
	}
	if(id != null) {
	    Path tmp = cachedir.resolve(id + ".tmp" + System.nanoTime());
	    try {
		Files.write(tmp, ret);
		Files.move(tmp, cachedir.resolve(id + ".spv"), StandardCopyOption.REPLACE_EXISTING);
	    } catch(IOException e) {
		try {Files.deleteIfExists(tmp);} catch(IOException e2) {}
	    }
	}
	return(ret);
    }

    public Path cachedir() {
	return(cachedir);
    }

    public void dispose() {
	synchronized(this) {
	    shaderc_compile_options_release(options);
	    shaderc_compiler_release(compiler);
	}
    }
}
