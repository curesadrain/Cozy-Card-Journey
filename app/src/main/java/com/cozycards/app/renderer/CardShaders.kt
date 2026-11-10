package com.cozycards.app.renderer

/**
 * GLSL ES 1.00 shaders (OpenGL ES 2.0, supported by every Android 10 device).
 * The same sources are compiled in a WebGL harness (tools/shader_check) during QA.
 */
object CardShaders {

    const val CARD_VERTEX = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUv;
attribute float aFace;
uniform mat4 uMvp;
uniform mat4 uModel;
uniform mat3 uNormalMatrix;
varying vec3 vWorldPos;
varying vec3 vLocalPos;
varying vec3 vNormal;
varying vec3 vLocalNormal;
varying vec2 vUv;
varying float vFace;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vWorldPos = wp.xyz;
    vLocalPos = aPos;
    vNormal = normalize(uNormalMatrix * aNormal);
    vLocalNormal = aNormal;
    vUv = aUv;
    vFace = aFace;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val CARD_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uFrame;
uniform sampler2D uArt;
uniform sampler2D uBack;
uniform vec3 uCamPos;
uniform vec3 uCamLocal;
uniform vec3 uLightDir;
uniform float uTime;
uniform float uFrameFoil;
uniform float uArtFoil;
uniform float uRarity;
uniform float uQuality;
uniform float uBackSheen;
uniform vec3 uEdgeColor;
uniform vec4 uArtRect;
uniform float uDepth;
varying vec3 vWorldPos;
varying vec3 vLocalPos;
varying vec3 vNormal;
varying vec3 vLocalNormal;
varying vec2 vUv;
varying float vFace;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
vec3 spectrum(float h) {
    return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - vWorldPos);
    vec3 L = normalize(uLightDir);
    // Paper / laminate micro-structure: perturb the normal with fine noise.
    if (vFace < 1.5 && uQuality > 0.5) {
        vec2 q = vUv * vec2(220.0, 300.0);
        vec3 bump = vec3(noise(q) - 0.5, noise(q + vec2(7.3, 2.1)) - 0.5, 0.0) * 0.07;
        N = normalize(N + bump);
    }
    vec3 H = normalize(L + V);
    float ndl = max(dot(N, L), 0.0);
    float ndh = max(dot(N, H), 0.0);
    float ndv = max(dot(N, V), 0.0);
    vec3 color;
    float foil = 0.0;
    float metal = 0.0;

    if (vFace < 0.5) {
        vec4 fr = texture2D(uFrame, vUv);
        // Parallax: the illustration sits slightly "below" the frame window.
        vec3 vl = normalize(uCamLocal - vLocalPos);
        vec2 shift = vec2(0.0);
        if (uQuality > 0.5) shift = -vl.xy / max(vl.z, 0.25) * uDepth * vec2(1.0, -1.0 / 1.4);
        vec2 center = (uArtRect.xy + uArtRect.zw) * 0.5;
        vec2 auv = center + (vUv - center) * 0.92 + shift;
        auv = clamp(auv, uArtRect.xy + 0.002, uArtRect.zw - 0.002);
        vec3 art = texture2D(uArt, auv).rgb;
        // Frame is premultiplied: composite frame over art.
        color = fr.rgb + art * (1.0 - fr.a);
        float frameMask = fr.a;
        foil = mix(uArtFoil, uFrameFoil, frameMask);
        metal = frameMask * step(0.5, uRarity);
    } else if (vFace < 1.5) {
        color = texture2D(uBack, vUv).rgb;
        foil = 0.0;
        metal = uBackSheen;
    } else {
        // Card edge: layered paper core with a faint stripe.
        float stripe = 0.85 + 0.15 * step(0.5, fract(vLocalPos.z * 160.0 + 0.5));
        color = uEdgeColor * stripe;
        metal = step(2.5, uRarity);
    }

    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 lit = color * (0.58 + 0.5 * ndl);

    // Metallic sheen on foil frames (follows the light like real foil stamping).
    if (metal > 0.0) {
        float sheen = pow(ndh, 10.0);
        lit = mix(lit, color * (0.45 + 1.3 * sheen) + vec3(sheen * 0.35), 0.75 * metal);
    }

    // Holographic iridescence: hue depends on viewing angle and position.
    if (foil > 0.0) {
        float h = dot(V.xy, vec2(1.6, 1.1)) * 1.3 + (vUv.x * 1.4 + vUv.y * 0.9) + uTime * 0.02;
        if (uQuality > 0.5) h += noise(vUv * 9.0) * 0.35;
        vec3 holo = spectrum(h);
        float angle = 0.35 + 0.65 * pow(1.0 - ndv, 1.5) + 0.5 * pow(ndh, 6.0);
        float bands = 0.6 + 0.4 * sin((vUv.x - vUv.y) * 40.0 + dot(V.xy, vec2(9.0, 6.0)));
        float amount = foil * angle * mix(1.0, bands, 0.5) * (0.55 + 0.45 * lum);
        lit = mix(lit, lit * 0.55 + holo * 0.8 * (0.6 + lum), clamp(amount, 0.0, 0.85));
    }

    // Sparkles (glitter foil) on Epic and Legendary cards.
    if (foil > 0.2 && uQuality > 0.5 && vFace < 0.5) {
        float density = uQuality > 1.5 ? 90.0 : 55.0;
        vec2 cell = floor(vUv * vec2(density, density * 1.4));
        float r = hash(cell);
        vec3 d = normalize(vec3(hash(cell + 1.7) - 0.5, hash(cell + 3.1) - 0.5, 1.4));
        float s = pow(max(dot(H, normalize(d.x * vec3(1.0, 0.0, 0.0) + d.y * vec3(0.0, 1.0, 0.0) + d.z * N)), 0.0), 420.0);
        lit += vec3(1.0, 0.97, 0.9) * s * step(0.55, r) * foil * 2.2;
    }

    // Laminated surface: clear-coat highlight and Fresnel reflection of a soft studio light.
    float clear = pow(ndh, vFace < 1.5 ? 70.0 : 30.0) * (vFace < 1.5 ? 0.55 : 0.3);
    float fres = pow(1.0 - ndv, 4.0) * 0.35;
    vec3 env = mix(vec3(0.95, 0.92, 1.0), vec3(1.0, 0.85, 0.9), 0.5 + 0.5 * N.y);
    lit += env * (clear + fres);

    gl_FragColor = vec4(clamp(lit, 0.0, 1.0), 1.0);
}
"""

    const val BG_VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.999, 1.0);
}
"""

    const val BG_FRAGMENT = """
precision mediump float;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec2 uShadow;
uniform vec2 uShadowSize;
uniform float uShadowAlpha;
uniform float uAspect;
varying vec2 vUv;
void main() {
    vec3 c = mix(uBottom, uTop, vUv.y);
    vec2 p = (vUv - vec2(0.5, 0.62)) * vec2(uAspect, 1.0);
    c += vec3(0.08, 0.06, 0.1) * exp(-dot(p, p) * 5.0);
    vec2 s = (vUv - uShadow) / uShadowSize;
    float sh = exp(-dot(s, s) * 2.2) * uShadowAlpha;
    c *= 1.0 - sh;
    float vig = smoothstep(1.25, 0.35, length((vUv - 0.5) * vec2(uAspect, 1.0)));
    gl_FragColor = vec4(c * (0.75 + 0.25 * vig), 1.0);
}
"""
}
