#!/usr/bin/env python3
"""Generate a forwarding shim for libarcore_sdk_c.so.

The Apace client imports the ARCore C API from libarcore_sdk_c.so. We replace
that library with a wrapper that dlopen()s the real one (renamed to
libarcore_sdk_c_real.so) and forwards every function. Later we override the
tracking functions with AprilTag-based poses.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = os.path.join(HERE, "arcore_c_api.h")
IMPORTS = os.path.join(HERE, "arcore_imports.txt")
OUT = os.path.join(HERE, "shim.c")

OVERRIDES = {
    "ArCamera_getViewMatrix", "ArCamera_getTrackingState",
    "ArCamera_getTrackingFailureReason", "ArSession_getAllTrackables",
    "ArTrackableList_create", "ArTrackableList_destroy",
    "ArTrackableList_getSize", "ArTrackableList_acquireItem",
    "ArTrackable_getTrackingState", "ArTrackable_release",
    "ArPlane_getCenterPose", "ArPlane_getExtentX", "ArPlane_getExtentZ",
    "ArPlane_getType", "ArPlane_acquireSubsumedBy",
    "ArSession_acquireNewAnchor", "ArAnchor_getPose",
    "ArAnchor_getTrackingState", "ArAnchor_detach", "ArAnchor_release",
    "ArFrame_acquirePointCloud", "ArPointCloud_getNumberOfPoints",
    "ArPointCloud_getData", "ArPointCloud_release",
    "ArPose_create", "ArPose_destroy", "ArPose_getMatrix",
    "ArCamera_getPose", "ArSession_update",
    "ArFrame_getAndroidSensorPose", "ArAugmentedImage_getCenterPose",
    "ArSession_setDisplayGeometry",
}

if os.environ.get("SHIM_NO_OVERRIDES"):
    OVERRIDES = set()

text = open(HEADER).read()
names = [l.strip() for l in open(IMPORTS) if l.strip().startswith("Ar")]


def matching_paren(s, i):
    depth = 0
    for j in range(i, len(s)):
        if s[j] == "(":
            depth += 1
        elif s[j] == ")":
            depth -= 1
            if depth == 0:
                return j
    return -1


def split_params(p):
    out, depth, cur = [], 0, ""
    for ch in p:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def param_name(p):
    p = p.split("=")[0].strip()
    p = re.sub(r"\[[^\]]*\]", "", p).strip()
    m = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", p)
    if not m:
        return None
    return m[-1]


funcs = []
missing = []
for name in names:
    found = None
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text):
        i = text.index("(", m.start())
        j = matching_paren(text, i)
        if j < 0:
            continue
        params = text[i + 1:j].strip()
        line_start = text.rfind("\n", 0, m.start()) + 1
        ret = text[line_start:m.start()].strip()
        # strip annotations and previous-line noise
        ret = re.sub(r"AR_[A-Z_]+\([^)]*\)", "", ret).strip()
        if not ret or ret.startswith("///") or ret.startswith("//"):
            # look at the previous non-comment line
            prev = text[:line_start].rstrip().split("\n")
            for line in reversed(prev):
                s = line.strip()
                if not s or s.startswith("///") or s.startswith("//"):
                    continue
                if s.startswith("AR_"):
                    continue
                ret = s
                break
        ret = re.sub(r"AR_[A-Z_]+\([^)]*\)", "", ret).strip()
        ret = re.sub(r"\bAR_[A-Z_]+\b", "", ret).strip()
        if not ret:
            continue
        # skip macro definitions / typedefs
        if "(" in ret or ret.startswith("#"):
            continue
        found = (ret, params)
        break
    if not found:
        missing.append(name)
        continue
    ret, params = found
    plist = split_params(params)
    args = []
    ok = True
    for p in plist:
        if p.strip() == "void":
            continue
        n = param_name(p)
        if not n:
            ok = False
            break
        args.append(n)
    if not ok:
        missing.append(name + " (param names)")
        continue
    funcs.append((name, ret, params, args))

print("generated", len(funcs), "functions; missing:", missing)

lines = []
lines.append('#include "arcore_c_api.h"')
lines.append('#include <dlfcn.h>')
lines.append('#include <android/log.h>')
lines.append('#include <string.h>')
lines.append('')
lines.append('#define TAG "ARCORE_SHIM"')
lines.append('static void *real_handle;')
lines.append('static void trace(const char *name);')
lines.append('')
for name, ret, params, args in funcs:
    lines.append(f'static {ret} (*real_{name})({params});')
lines.append('')
lines.append('__attribute__((constructor)) static void shim_init(void) {')
lines.append('    real_handle = dlopen("libarcore_sdk_c_real.so", RTLD_NOW | RTLD_LOCAL);')
lines.append('    if (!real_handle) {')
lines.append('        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen real failed: %s", dlerror());')
lines.append('        return;')
lines.append('    }')
for name, ret, params, args in funcs:
    lines.append(f'    real_{name} = ({ret} (*)({params}))dlsym(real_handle, "{name}");')
    lines.append(f'    if (!real_{name}) __android_log_print(ANDROID_LOG_ERROR, TAG, "missing symbol: {name}");')
lines.append('    __android_log_print(ANDROID_LOG_INFO, TAG, "shim initialized");')
lines.append('}')
lines.append('')
for name, ret, params, args in funcs:
    if name in OVERRIDES:
        continue
    call = f'real_{name}({", ".join(args)})'
    if ret.strip() == "void":
        body = f'    {call};'
    else:
        body = f'    return {call};'
    lines.append(f'{ret} {name}({params}) {{')
    lines.append(f'    trace("{name}");')
    lines.append(body)
    lines.append('}')
    lines.append('')

lines.append('#include "%s"' % os.environ.get("SHIM_INC", "overrides.inc"))
open(OUT, "w").write("\n".join(lines) + "\n")
print("written", OUT)
