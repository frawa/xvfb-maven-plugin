package com.github.zetten.maven.xvfb;

/*
 * #%L
 * Xvfb Maven Plugin
 * %%
 * Copyright (C) 2015 Peter van Zetten
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.codehaus.plexus.util.cli.CommandLineUtils;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * <p>
 * Start a new Xvfb server. Optionally export the DISPLAY variable for the new server for use in other phases.
 * </p>
 * <p>
 * By default, the property "xvfb.display" will be set, and may be used in other plugin executions (occurring after this
 * xvfb:run) in the standard manner.
 * </p>
 * <p>
 * Note that if the "display" parameter is explicitly set this mojo may be considered thread-safe. The automatic X
 * display port determination could lead to a race condition with parallel executions.
 * </p>
 */
@Mojo(name = "run", threadSafe = true, defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
public class XvfbRunMojo extends AbstractXvfbMojo {

	private static final String TMPDIR_KEY = "java.io.tmpdir";
	private static final String TMPFILE_PREFIX = ".mvn_Xvfb_display";

	/**
	 * Create a new Mojo and register the shutdown handler to ensure no Xvfb processes are left hanging around in the
	 * event that xvfb:stop is not run for any reason.
	 */
	public XvfbRunMojo() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				if (getPluginContext().containsKey(XVFB_PROCESS_KEY)) {
					Process process = (Process) getPluginContext().get(XVFB_PROCESS_KEY);
					if (process != null) {
						destroyXvfb(process);
						getPluginContext().remove(XVFB_PROCESS_KEY);
					}
				}

				if (getPluginContext().containsKey(XVFB_LOCKFILE_KEY)) {
					File lockFile = (File) getPluginContext().get(XVFB_LOCKFILE_KEY);
					if (lockFile != null) {
						getLog().debug("Deleting lockfile: " + lockFile.getAbsolutePath());
						lockFile.delete();
						getPluginContext().remove(XVFB_LOCKFILE_KEY);
					}
				}
			}
		});
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (display != null) {
			// If a fixed display variable is configured, use it
			if (isDisplayActive(display)) {
				throw new MojoExecutionException("Cannot start Xvfb: X display already in use.");
			}

			try {
				startXvfb(display);
				return;
			} catch (Exception e) {
				throw new MojoExecutionException("Could not launch Xvfb.", e);
			}
		} else {
			// Otherwise, search for an available display, retrying until one is found in the requested range
			try (ServerSocket s = getAvailableDisplaySocket()) {
				String d = ":" + (s.getLocalPort() - xDisplayPortBase);
				// Close the placeholder socket before launching Xvfb on the same port
				s.close();
				getLog().info("Launching Xvfb on " + d);
				startXvfb(d);
				return;
			} catch (Exception e) {
				getLog().debug("Unable to start Xvfb by searching for a free port.", e);
			}

			throw new MojoExecutionException("Could not launch Xvfb.");
		}
	}

	/**
	 * Launch Xvfb on the given X display.
	 * 
	 * @param d
	 *            The X display to use, e.g. ":20".
	 * @return The Process representing the Xvfb server.
	 * @throws Exception 
	 */
	@SuppressWarnings("unchecked")
	private void startXvfb(String d) throws Exception {
		List<String> command = Lists.newArrayList(xvfbBinary);
		command.add(d);
		if ( xvfbArgs!=null ) {
			command.addAll(xvfbArgs);
		}
		if ( xvfbArgLine!=null ) {
			String[] args = CommandLineUtils.translateCommandline(xvfbArgLine);
			command.addAll(Arrays.asList(args));
		}

		if (!Strings.isNullOrEmpty(fbdir)) {
			File dir = new File(fbdir);
			if (!dir.exists()) {
				dir.mkdirs();
			}
			command.add("-fbdir");
			command.add(fbdir);
		}

		final ProcessBuilder pb = new ProcessBuilder(command);

		getLog().info("Attempting to launch Xvfb with arguments: " + command);
		Process process = pb.start();

		if (process != null) {
			getLog().info("Xvfb launched.");

			// Publish details of Xvfb instance
			getPluginContext().put(XVFB_PROCESS_KEY, process);
			if (setDisplayMavenProp) {
				setPropDisplay(d);
			}
			if (setDisplayEnvVar) {
				setEnvDisplay(d);
			}
		}
	}

	/**
	 * Iterate through the range specified by xDisplayPortBase, xDisplayDefaultNumber, and maxDisplaysToSearch until a
	 * free port is found. If a socket can be created against the port, a lockfile is also created to reserve the port
	 * against other executions of the plugin.
	 * 
	 * @return The created socket, if one is found in the given bounds.
	 * @throws MojoExecutionException
	 *             If no socket is able to be created.
	 */
	@SuppressWarnings("unchecked")
	private ServerSocket getAvailableDisplaySocket() throws MojoExecutionException {
		int n = xDisplayDefaultNumber;

		while (n <= xDisplayDefaultNumber + maxDisplaysToSearch) {
			int port = xDisplayPortBase + n;
			File lockFile = Paths.get(System.getProperty(TMPDIR_KEY), TMPFILE_PREFIX + port).toFile();

			try {
				if (!lockFile.exists()) {
					// The socket must still be tested, as the port may be used by other processes
					getLog().debug("Trying to reserve display :" + n);
					ServerSocket socket = new ServerSocket(port);
					lockFile.createNewFile();
					getLog().debug("Using lockfile: " + lockFile.getAbsolutePath());
					getPluginContext().put(XVFB_LOCKFILE_KEY, lockFile);
					return socket;
				}
			} catch (IOException e) {
				if (doRetry) {
					// swallow the exception with a log message
					getLog().debug("Failed to start Xvfb on display :" + n + ", retrying.");
				} else {
					throw new MojoExecutionException("Could not find a usable display and doRetry is false.");
				}
			}

			n++;
		}
		throw new MojoExecutionException("Could not find a usable display in the given bounds.");
	}

	/**
	 * Set the Maven property defined by 'displayMavenProp' to the given value. This property should then be usable by
	 * subsequent goals and plugins in the current reactor.
	 */
	private void setPropDisplay(String d) {
		getLog().info("Setting Maven property '" + displayMavenProp + "' to: " + d);
		mavenProject.getProperties().put(displayMavenProp, d);
	}

	/**
	 * <p>
	 * This is a <em>very</em> dirty hack for setting the DISPLAY environment variable within the running JVM. In
	 * theory, subsequent methods or launched Processes will see the correct DISPLAY value.
	 * </p>
	 * <p>
	 * See: {@link http://stackoverflow.com/a/19040660/137403}
	 * </p>
	 */
	@SuppressWarnings("all")
	private void setEnvDisplay(String d) {
		getLog().info("Setting DISPLAY environment variable to: " + d);
		try {
			Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
			Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
			theEnvironmentField.setAccessible(true);

			Class<?> variableClass = Class.forName("java.lang.ProcessEnvironment$Variable");
			Method convertToVariable = variableClass.getMethod("valueOf", String.class);
			convertToVariable.setAccessible(true);

			Class<?> valueClass = Class.forName("java.lang.ProcessEnvironment$Value");
			Method convertToValue = valueClass.getMethod("valueOf", String.class);
			convertToValue.setAccessible(true);

			Object sampleVariable = convertToVariable.invoke(null, "");
			Object sampleValue = convertToValue.invoke(null, "");
			Map<Object, Object> env = (Map<Object, Object>) theEnvironmentField.get(null);
			for (Map.Entry<String, String> e : System.getenv().entrySet()) {
				Object var, val;
				if (e.getKey().equals("DISPLAY")) {
					var = (Object) convertToVariable.invoke(null, "DISPLAY");
					val = (Object) convertToValue.invoke(null, d);
				} else {
					var = (Object) convertToVariable.invoke(null, e.getKey());
					val = (Object) convertToValue.invoke(null, e.getValue());
				}
				env.put(var, val);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Check if the given display identifier is active or not.
	 */
	private boolean isDisplayActive(String d) {
		try {
			Integer port = decodeDisplayPort(d);
			Socket socket = new Socket("localhost", port);
			socket.close();
			return true;
		} catch (ConnectException e) {
			return false;
		} catch (UnknownHostException e) {
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * <p>
	 * Convert the DISPLAY variable into a port number.
	 * </p>
	 * <p>
	 * Typically X displays map to TCP ports like :1 -> 6001
	 * </p>
	 */
	private Integer decodeDisplayPort(String d) {
		Matcher m = Pattern.compile("[^:]*:(\\d*)(?:\\.(\\d*))?").matcher(d);

		if (!m.matches()) {
			throw new IllegalArgumentException("Requested DISPLAY variable is not in the correct format (e.g. ':20')");
		}

		Integer i = Integer.parseInt(m.group(1));

		return xDisplayPortBase + i;
	}

}
