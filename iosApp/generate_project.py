#!/usr/bin/env python3
"""Generate this small Xcode host with standard-library Python; no XcodeGen dependency."""

from pathlib import Path
import hashlib
import plistlib

root = Path(__file__).parent
objects = []


def uid(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()


def obj(name, body):
    objects.append(f"{uid(name)} = {{ {body} }};")
    return uid(name)


def ref(name):
    return uid(name)


sources = []
files = []
files.append(
    obj(
        "config",
        'isa = PBXFileReference; lastKnownFileType = text.xcconfig; path = Config.xcconfig; sourceTree = "<group>";',
    )
)
for file in ["NearbyLabApp.swift", "AppleNearbyTransport.swift"]:
    files.append(
        obj(
            file,
            f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{file}"; sourceTree = "<group>";',
        )
    )
    sources.append(obj(file + "build", f"isa = PBXBuildFile; fileRef = {ref(file)};"))
obj(
    "product",
    "isa = PBXFileReference; explicitFileType = wrapper.application; path = NearbyLab.app; sourceTree = BUILT_PRODUCTS_DIR;",
)
obj(
    "package",
    'isa = XCRemoteSwiftPackageReference; repositoryURL = "https://github.com/google/nearby.git"; requirement = { kind = revision; revision = a95eba75022cc1d00f0898bbde5e6ad03ee71ba2; };',
)
obj(
    "nearbyproduct",
    f'isa = XCSwiftPackageProductDependency; package = {ref("package")}; productName = NearbyConnections;',
)
obj("nearbybuild", f'isa = PBXBuildFile; productRef = {ref("nearbyproduct")};')
obj(
    "sources",
    f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(sources)},); runOnlyForDeploymentPostprocessing = 0;',
)
obj(
    "frameworks",
    f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({ref("nearbybuild")},); runOnlyForDeploymentPostprocessing = 0;',
)
obj(
    "resources",
    "isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;",
)
script = (
    'set -eu\ncd "$SRCROOT/.."\n./gradlew :shared:embedAndSignAppleFrameworkForXcode\n'
)


def quoted(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


obj(
    "kotlinphase",
    f'isa = PBXShellScriptBuildPhase; alwaysOutOfDate = 1; buildActionMask = 2147483647; files = (); inputPaths = (); outputPaths = (); name = "Build Kotlin framework"; shellPath = /bin/sh; shellScript = {quoted(script)}; runOnlyForDeploymentPostprocessing = 0;',
)
obj(
    "products",
    f'isa = PBXGroup; children = ({ref("product")},); name = Products; sourceTree = "<group>";',
)
obj(
    "group",
    f'isa = PBXGroup; children = ({",".join(files)},{ref("products")},); sourceTree = "<group>";',
)
for config in ["Debug", "Release"]:
    obj(
        "project" + config,
        f"isa = XCBuildConfiguration; name = {config}; buildSettings = {{ CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SDKROOT = iphoneos; ENABLE_USER_SCRIPT_SANDBOXING = NO; }};",
    )
    obj(
        "target" + config,
        f"""isa = XCBuildConfiguration; name = {config}; baseConfigurationReference = {ref('config')}; buildSettings = {{
        ARCHS = arm64; CODE_SIGN_STYLE = Automatic; DEVELOPMENT_TEAM = "$(NEARBY_DEVELOPMENT_TEAM)";
        PRODUCT_BUNDLE_IDENTIFIER = "$(NEARBY_BUNDLE_ID_PREFIX)"; PRODUCT_NAME = "$(TARGET_NAME)";
        INFOPLIST_FILE = Info.plist; CODE_SIGN_ENTITLEMENTS = NearbyLab.entitlements;
        SWIFT_VERSION = 5.0; CURRENT_PROJECT_VERSION = 2; MARKETING_VERSION = 0.2.0;
        TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
        SUPPORTS_MACCATALYST = NO; ENABLE_USER_SCRIPT_SANDBOXING = NO;
        FRAMEWORK_SEARCH_PATHS = ("$(inherited)", "$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)");
        OTHER_LDFLAGS = ("$(inherited)", "-framework", NearbyShared, "-lsqlite3");
        LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks");
        SWIFT_OPTIMIZATION_LEVEL = "{'-Onone' if config == 'Debug' else '-O'}";
    }};""",
    )
for scope in ["project", "target"]:
    obj(
        scope + "configs",
        f'isa = XCConfigurationList; buildConfigurations = ({ref(scope+"Debug")},{ref(scope+"Release")},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;',
    )
# A separate target shares compiled SDK dependencies but has no restricted entitlements.
obj(
    "personalproduct",
    "isa = PBXFileReference; explicitFileType = wrapper.application; path = NearbyLabPersonal.app; sourceTree = BUILT_PRODUCTS_DIR;",
)
obj(
    "personalsources",
    f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(sources)},); runOnlyForDeploymentPostprocessing = 0;',
)
obj(
    "personalframeworks",
    f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({ref("nearbybuild")},); runOnlyForDeploymentPostprocessing = 0;',
)
obj(
    "personalresources",
    "isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;",
)
obj(
    "personalkotlinphase",
    f'isa = PBXShellScriptBuildPhase; alwaysOutOfDate = 1; buildActionMask = 2147483647; files = (); inputPaths = (); outputPaths = (); name = "Build Kotlin framework"; shellPath = /bin/sh; shellScript = {quoted(script)}; runOnlyForDeploymentPostprocessing = 0;',
)
for config in ["Debug", "Release"]:
    obj(
        "personal" + config,
        f"""isa = XCBuildConfiguration; name = {config}; baseConfigurationReference = {ref('config')}; buildSettings = {{
        ARCHS = arm64; CODE_SIGN_STYLE = Automatic; DEVELOPMENT_TEAM = "$(NEARBY_DEVELOPMENT_TEAM)";
        PRODUCT_BUNDLE_IDENTIFIER = "$(NEARBY_BUNDLE_ID_PREFIX).personal"; PRODUCT_NAME = "$(TARGET_NAME)";
        INFOPLIST_FILE = Info.plist; CODE_SIGN_ENTITLEMENTS = NearbyLabPersonal.entitlements;
        SWIFT_VERSION = 5.0; SWIFT_ACTIVE_COMPILATION_CONDITIONS = "$(inherited) NEARBY_PERSONAL_TEAM";
        CURRENT_PROJECT_VERSION = 2; MARKETING_VERSION = 0.2.0;
        TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
        SUPPORTS_MACCATALYST = NO; ENABLE_USER_SCRIPT_SANDBOXING = NO;
        FRAMEWORK_SEARCH_PATHS = ("$(inherited)", "$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)");
        OTHER_LDFLAGS = ("$(inherited)", "-framework", NearbyShared, "-lsqlite3");
        LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks");
        SWIFT_OPTIMIZATION_LEVEL = "{'-Onone' if config == 'Debug' else '-O'}";
    }};""",
    )
obj(
    "personalconfigs",
    f'isa = XCConfigurationList; buildConfigurations = ({ref("personalDebug")},{ref("personalRelease")},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;',
)
obj(
    "personaltarget",
    f"""isa = PBXNativeTarget; name = NearbyLabPersonal; productName = NearbyLabPersonal;
    productReference = {ref('personalproduct')}; productType = "com.apple.product-type.application";
    buildConfigurationList = {ref('personalconfigs')};
    buildPhases = ({ref('personalkotlinphase')},{ref('personalsources')},{ref('personalframeworks')},{ref('personalresources')},);
    buildRules = (); dependencies = (); packageProductDependencies = ({ref('nearbyproduct')},);
""",
)
obj(
    "target",
    f"""isa = PBXNativeTarget; name = NearbyLab; productName = NearbyLab;
    productReference = {ref('product')}; productType = "com.apple.product-type.application";
    buildConfigurationList = {ref('targetconfigs')};
    buildPhases = ({ref('kotlinphase')},{ref('sources')},{ref('frameworks')},{ref('resources')},);
    buildRules = (); dependencies = (); packageProductDependencies = ({ref('nearbyproduct')},);
""",
)
obj(
    "project",
    f"""isa = PBXProject; attributes = {{ LastUpgradeCheck = 2650; }};
    buildConfigurationList = {ref('projectconfigs')}; compatibilityVersion = "Xcode 14.0";
    developmentRegion = en; knownRegions = (en,ru,Base,); mainGroup = {ref('group')};
    productRefGroup = {ref('products')}; projectDirPath = ""; projectRoot = "";
    targets = ({ref('target')},{ref('personaltarget')},); packageReferences = ({ref('package')},);
""",
)
project = root / "NearbyLab.xcodeproj"
project.mkdir(exist_ok=True)
(project / "project.pbxproj").write_text(
    "// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n"
    + "\n".join(objects)
    + "\n}; rootObject = "
    + ref("project")
    + ";}\n"
)
scheme = project / "xcshareddata/xcschemes"
scheme.mkdir(parents=True, exist_ok=True)
scheme_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2650" version="1.3"><BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForRunning="YES" buildForTesting="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ref('target')}" BuildableName="NearbyLab.app" BlueprintName="NearbyLab" ReferencedContainer="container:NearbyLab.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction><LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ref('target')}" BuildableName="NearbyLab.app" BlueprintName="NearbyLab" ReferencedContainer="container:NearbyLab.xcodeproj"/></BuildableProductRunnable></LaunchAction></Scheme>"""
(scheme / "NearbyLab.xcscheme").write_text(scheme_xml)
(scheme / "NearbyLabPersonal.xcscheme").write_text(
    scheme_xml.replace(ref("target"), ref("personaltarget"))
    .replace('BuildableName="NearbyLab.app"', 'BuildableName="NearbyLabPersonal.app"')
    .replace('BlueprintName="NearbyLab"', 'BlueprintName="NearbyLabPersonal"')
)
# Pinned SDK: internal/platform/nsd_service_info.h uses 6 bytes (12 hex chars).
# Current web setup says "12 bytes"; follow actual GenerateServiceType implementation.
service = hashlib.sha256(b"org.bigmeco.nearbylab.v1").digest()[:6].hex().upper()
plist = {
    "CFBundleExecutable": "$(EXECUTABLE_NAME)",
    "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
    "CFBundleName": "$(PRODUCT_NAME)",
    "CFBundlePackageType": "APPL",
    "CFBundleShortVersionString": "0.2.0",
    "CFBundleVersion": "2",
    "LSRequiresIPhoneOS": True,
    "UILaunchScreen": {},
    "CADisableMinimumFrameDurationOnPhone": True,
    "UISupportedInterfaceOrientations": [
        "UIInterfaceOrientationPortrait",
        "UIInterfaceOrientationLandscapeLeft",
        "UIInterfaceOrientationLandscapeRight",
    ],
    "NSBluetoothAlwaysUsageDescription": "Find and connect to nearby devices running NearbyLab.",
    "NSLocalNetworkUsageDescription": "Exchange test messages with NearbyLab peers on the local network.",
    "NSLocationWhenInUseUsageDescription": "Nearby SDK uses location access when connecting to a Wi-Fi hotspot.",
    "NSBonjourServices": [f"_{service}._tcp"],
}
(root / "Info.plist").write_bytes(plistlib.dumps(plist))
(root / "NearbyLab.entitlements").write_bytes(
    plistlib.dumps(
        {
            "com.apple.developer.networking.HotspotConfiguration": True,
            "com.apple.developer.networking.wifi-info": True,
        }
    )
)
(root / "NearbyLabPersonal.entitlements").write_bytes(plistlib.dumps({}))
print("Generated NearbyLab.xcodeproj; Bonjour:", plist["NSBonjourServices"])
