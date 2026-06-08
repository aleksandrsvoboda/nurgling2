package haven;

import nurgling.NConfig;
import nurgling.headless.Headless;
import nurgling.headless.HeadlessConfig;
import nurgling.headless.HeadlessMain;
import nurgling.sessions.NBootstrap;
import nurgling.sessions.NRemoteUI;
import haven.iosys.tk.Toolkit;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Nurgling launcher.
 *
 * Performs nurgling-specific pre-initialisation (config, localisation, logging,
 * error handling, headless dispatch, multi-session bootstrap wiring) and then
 * delegates all windowing/rendering to {@link haven.Client} (the new iosys
 * toolkit architecture). The former AWT-Frame role of this class is now handled
 * entirely by Client + the toolkit Windeye.
 */
public class MainFrame {
    /** Nurgling configuration, consumed by NCore and the headless entry point. */
    public static NConfig config;
    public static final Config.Variable<Boolean> status = Config.Variable.propb("haven.status", false);

    public static void initlocale() {
	try {
	    /* XXX? Localization is nice and all, but the game as a whole
	     * currently isn't internationalized, so using the local settings
	     * for things like number formatting just leads to inconsistency. */
	    Locale.setDefault(Locale.US);
	} catch(Exception e) {
	    new Warning(e, "locale initialization failed").issue();
	}
    }

    public static void initawt() {
	try {
	    System.setProperty("apple.awt.application.name", "Haven & Hearth");
	    javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
	} catch(Exception e) {
	    new Warning(e, "AWT initialization failed").issue();
	}
    }

    static {
	initlocale();
	initawt();
    }

    /** Resource setup; delegated to the new Client entry point. */
    public static void setupres() {
	Client.setupres();
    }

    public static final Config.Variable<Path> loadwaited = Config.Variable.propp("haven.loadwaited", "");
    public static final Config.Variable<Path> allused = Config.Variable.propp("haven.allused", "");
    public static void resdump() {
	dumplist(Resource.remote().loadwaited(), loadwaited.get());
	dumplist(Resource.remote().cached(), allused.get());
	if(ResCache.global != null) {
	    try {
		Writer w = new OutputStreamWriter(ResCache.global.store("tmp/allused"), "UTF-8");
		try {
		    Resource.dumplist(Resource.remote().used(), w);
		} finally {
		    w.close();
		}
	    } catch(IOException e) {}
	}
    }

    private static void dumplist(Collection<Resource> list, Path fn) {
	try {
	    if(fn != null) {
		try(Writer w = Files.newBufferedWriter(fn, Utils.utf8)) {
		    Resource.dumplist(list, w);
		}
	    }
	} catch(IOException e) {
	    throw(new RuntimeException(e));
	}
    }

    public static void status(String state) {
	if(status.get()) {
	    System.out.println("hafen:status:" + state);
	    System.out.flush();
	}
    }

    private static void javabughack() throws InterruptedException {
	/* Work around a stupid deadlock bug in AWT. */
	try {
	    javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
		    public void run() {
			PrintStream bitbucket = new PrintStream(new ByteArrayOutputStream());
			bitbucket.print(LoginScreen.textf);
			bitbucket.print(LoginScreen.textfs);
		    }
		});
	} catch(java.lang.reflect.InvocationTargetException e) {
	    /* Oh, how I love Swing! */
	    throw(new Error(e));
	}
	/* Work around another deadlock bug in Sun's JNLP client. */
	javax.imageio.spi.IIORegistry.getDefaultInstance();
    }

    private static void main2(String[] args) {
	for(String value : args) {
	    if(value.contains("-bots")) {
		NConfig.enableBotMod(args[1]);
		break;
	    }
	}

//	Config.cmdline(args);
	status("start");

	/* Multi-session: nurgling login bootstrap. */
	Bootstrap.setFactory(NBootstrap::new);

	try {
	    javabughack();
	} catch(InterruptedException e) {
	    return;
	}
	setupres();

	Client cl = new Client(Toolkit.instance());
	try {
	    UI.Runner fun = cl.new Main();
	    if(Bootstrap.replay.get() != null) {
		try {
		    Transport.Playback player = new Transport.Playback(Files.newBufferedReader(Bootstrap.replay.get(), Utils.utf8));
		    fun = new NRemoteUI(new Session(player, new Session.User("Playback")));
		    player.start();
		} catch(IOException e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    } else if(Bootstrap.servargs.get() != null) {
		try {
		    fun = new NRemoteUI(Client.connect(Bootstrap.servargs.get()));
		} catch(Client.ConnectionError e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    }
	    status("visible");
	    cl.run(fun);
	} finally {
	    cl.dispose();
	}
	resdump();
	status("exit");
	System.exit(0);
    }

    public static void main(final String[] args) {
	// Check for headless mode FIRST, before any other initialization
	if(Headless.hasHeadlessFlag(args)) {
	    // Set headless mode before any AWT classes load
	    Headless.setHeadless(true);

	    // Check if -bots config file is also provided
	    String botsConfigPath = extractBotsPath(args);
	    if(botsConfigPath != null) {
		// Headless mode with bot config file (from electron-hh-autorunner)
		HeadlessConfig config = HeadlessConfig.parseFromFile(botsConfigPath);
		HeadlessMain.runWithConfig(config);
	    } else {
		// Headless mode with CLI args
		HeadlessMain.main(args);
	    }
	    return;
	}

	config = new NConfig();
	config.read();

	// Apply saved language after config is loaded
	nurgling.i18n.L10n.applySavedLanguage();

	// Initialize FileLogger and redirect System.err as early as possible
	haven.error.FileLogger.redirectSystemErr();
	haven.error.FileLogger.log("Application starting...");

	/* Set up the error handler as early as humanly possible. */
	ThreadGroup g = new ThreadGroup("Haven main group");
	String ed = Utils.getprop("haven.errorurl", "");
	if(ed.equals("stderr")) {
	    g = new haven.error.SimpleHandler("Haven main group", true);
	} else if(!ed.equals("")) {
	    try {
		final haven.error.ErrorHandler hg = new haven.error.ErrorHandler(new java.net.URI(ed).toURL());
		hg.sethandler(new haven.error.ErrorGui(null) {
			public void errorsent() {
			    hg.interrupt();
			}
		    });
		g = hg;
		new DeadlockWatchdog(hg).start();
	    } catch(java.net.MalformedURLException | java.net.URISyntaxException e) {
		haven.error.FileLogger.logError("Failed to initialize ErrorHandler", e);
	    }
	}

	// Add global uncaught exception handler
	Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
	    public void uncaughtException(Thread t, Throwable e) {
		haven.error.FileLogger.logError("Uncaught exception in thread: " + t.getName(), e);
	    }
	});

	Thread main = new HackThread(g, () -> main2(args), "Haven main thread");
	main.start();
    }

    /**
     * Extract the -bots config file path from command line arguments.
     * Returns null if -bots flag is not present.
     */
    private static String extractBotsPath(String[] args) {
	for(int i = 0; i < args.length; i++) {
	    if(args[i].equals("-bots") && i + 1 < args.length) {
		return args[i + 1];
	    }
	}
	return null;
    }
}
