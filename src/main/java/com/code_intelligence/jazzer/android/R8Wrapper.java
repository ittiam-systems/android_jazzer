/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.code_intelligence.jazzer.r8;

import static java.lang.System.exit;

import com.code_intelligence.jazzer.driver.OfflineInstrumentor;
import com.code_intelligence.jazzer.utils.ZipUtils;
import com.code_intelligence.jazzer.driver.Opt;
import com.code_intelligence.jazzer.utils.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ClassNotFoundException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class R8Wrapper {
  private static void setOptions() throws IOException {
    Path dumpClassesDir = Files.createTempDirectory("instrumented_classes");
    List<String> jazzerOpts = new ArrayList<>();
    jazzerOpts.add("--dump_classes_dir=" + dumpClassesDir.toString());
    applyConfig(jazzerOpts);

    Opt.registerAndValidateCommandLineArgs(Opt.parseJazzerArgs(jazzerOpts));
  }

  private static void applyConfig(List<String> jazzerOpts) {
    //TODO: Find an alternative for ths hardcoded configuration path
    File configFile = new File("/work/sahil_workspace/master/prebuilts/jazzer/jazzer_instrumentation_config.json");

    if (!configFile.exists()) {
      System.out.println("Config file does not exist");
      return;
    }

    try {
      JsonObject jsonObject = JsonParser.parseReader(new FileReader(configFile)).getAsJsonObject();

      for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
        String flag = entry.getKey();
        JsonElement value = entry.getValue();

        if (value.isJsonArray()) {
          // Handle list of strings
          List<String> parts = new ArrayList<>();
          for (JsonElement element : value.getAsJsonArray()) {
              parts.add(element.getAsString());
          }
          // Example: --instrumentation_excludes=foo:bar:baz
          jazzerOpts.add("--" + flag + "=" + String.join(":", parts));

        } else if (value.isJsonPrimitive()) {
          JsonPrimitive primitive = value.getAsJsonPrimitive();

          if (primitive.isBoolean()) {
            // Example: --some_flag=true or false
            jazzerOpts.add("--" + flag + "=" + primitive.getAsBoolean());
          } else if (primitive.isString()) {
            // Example: --target_class=com.example.MyFuzzer
            jazzerOpts.add("--" + flag + "=" + primitive.getAsString());
          } else if (primitive.isNumber()) {
            // Optional: number handling
            jazzerOpts.add("--" + flag + "=" + primitive.getAsNumber().toString());
          }

        } else {
          System.out.println("Unsupported config type for: " + flag);
        }
      }

    } catch (FileNotFoundException e) {
        System.out.println("Config file was expected but not found: " + e.getMessage());
    }
  }

  public static void main(String[] args) throws Throwable {
    R8Wrapper.setOptions();
    List<String> jarfiles = R8Wrapper.parseJarFile(args);

    try {
      Class<?> soongR8Wrapper = Class.forName(
          "com.android.tools.r8wrappers.R8Wrapper", false, R8Wrapper.class.getClassLoader());
      MethodHandle main = MethodHandles.lookup().findStatic(
          soongR8Wrapper, "main", MethodType.methodType(void.class, String[].class));

      // found com.android.tools.r8warpper.R8Wrapper
      // don't add native libs, we are in AOSP and Soong has special code for this
      boolean instrumentationSuccess = OfflineInstrumentor.instrumentJars(jarfiles, false);
      if (!instrumentationSuccess) {
        exit(1);
      }

      // extracting tha jazzer_android.jar and get the jazzer_bootstrap.jar file
      File jazzerForAndroid =
      ZipUtils.extractFileFromJar("/com/code_intelligence/jazzer/android/jazzer_android.jar");

      File bootstrapJar = Files.createTempFile("jazzer_bootstrap", ".jar").toFile();
      ZipUtils.extractFileFromJar(jazzerForAndroid.getPath(), "com/code_intelligence/jazzer/runtime/jazzer_bootstrap.jar", bootstrapJar.getPath());
      bootstrapJar.deleteOnExit();

      String[] newArgs = new String[args.length + 2];
      System.arraycopy(args, 0, newArgs, 0, args.length);
      newArgs[args.length] = "-libraryjars";
      newArgs[args.length + 1] = bootstrapJar.getAbsolutePath();
      
      main.invokeExact(newArgs);
      return;
    } catch (ClassNotFoundException cnfe) {
      // This is ok, we wouldn't expect this class to be found outside of AOSP
      System.out.println("No wrapper function found");
    }

    try {
      Class<?> r8 =
          Class.forName("com.android.tools.r8.R8", false, R8Wrapper.class.getClassLoader());
      MethodHandle main = MethodHandles.lookup().findStatic(
          r8, "main", MethodType.methodType(void.class, String[].class));

      // Calling normal R8 functions.
      // TODO: this path needs more testing
      boolean instrumentationSuccess = OfflineInstrumentor.instrumentJars(jarfiles, true);
      if (!instrumentationSuccess) {
        exit(1);
      }

      main.invokeExact(args);
    } catch (Exception e) {
      System.out.println(e);
    }
  }

  private static List<String> parseJarFile(String[] args) {
    List<String> jarlist = new ArrayList<String>();

    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-injars")) {
        i += 1;
        while (i < args.length) {
          if (args[i].startsWith("-")) {
            // next flag, break;
            break;
          }

          jarlist.add(args[i]);
          i += 1;
        }
      }
    }
    return jarlist;
  }
}
