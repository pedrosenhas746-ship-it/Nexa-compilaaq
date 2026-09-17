using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace Nexa.Editor.CI
{
    public static class NexaQuestCiBuild
    {
        public static void BuildAndroid()
        {
            var output = Environment.GetEnvironmentVariable("NEXAQUEST_APK") ?? "Build/NexaQuest-Phase12.apk";
            Directory.CreateDirectory(Path.GetDirectoryName(output) ?? "Build");

            EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, "com.nexa.questcraft");
            PlayerSettings.productName = "Nexa QuestCraft";
            PlayerSettings.bundleVersion = "0.1.2";
            PlayerSettings.Android.bundleVersionCode = 12;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevel34;
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.OpenGLES3 });
            PlayerSettings.MTRendering = true;

            var scenes = EditorBuildSettings.scenes
                .Where(s => s.enabled)
                .Select(s => s.path)
                .ToArray();
            if (scenes.Length == 0)
                throw new Exception("QCXR project has no enabled build scenes");

            var options = new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = output,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None
            };

            var report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != UnityEditor.Build.Reporting.BuildResult.Succeeded)
                throw new Exception("Unity Android build failed: " + report.summary.result);

            Debug.Log($"Nexa QuestCraft APK built: {output} ({report.summary.totalSize} bytes)");
        }
    }
}
