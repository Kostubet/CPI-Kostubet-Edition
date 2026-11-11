// SPDX-License-Identifier: GPL-3.0-only
// ShadowEdge AGSL from ONextBox 17.0.0.100, MiToverG422 and contributors.
package com.cosmod.launcherglass.ui

internal const val ColorOs17ShadowEdgeShader = """
uniform float u_commonArray[12];
uniform float u_shadow;
uniform float u_shadowArray[16];
uniform float u_edge;
uniform float u_edgeArray[16];

const float EPS = 1e-6;
const float EFFECT_PARAM_THRESHOLD = 0.001;
const float DEFAULT_TRANSITION_PIXELS = 4.0;
const float kAbs = 1.0;
const float rRef = 80.0;
const float kMax = 26.6;
const float junctionXBand = 70.0;
const float junctionYBand = 60.0;

struct CommonDataRect {
    vec2 halfResolution;
    vec2 pos;
    vec2 halfSize;
    float coner;
    float weight;
    float preCalcMinC;
};

float getQuadrantValue(vec2 pos, vec4 values) {
    vec2 s = step(vec2(0.0), pos);
    float valueTop = mix(values.x, values.y, s.x);
    float valueBot = mix(values.w, values.z, s.x);
    return mix(valueTop, valueBot, s.y);
}

float noise(vec2 p) {
    return fract(sin(dot(p.yx, vec2(79.1214, 78.233))) * 43760.5453);
}

float softLight(float A, float B) {
    float l = B - (1.0 - 2.0 * A) * B * (1.0 - B);
    float h = B + (2.0 * A - 1.0) * (sqrt(B) - B);
    return mix(l, h, step(0.5, A));
}

float smoothAbs(float x, float k) {
    return sqrt(x * x + k * k);
}

float smoothMaxSqrt(float a, float b, float k) {
    float d = a - b;
    return 0.5 * (a + b + sqrt(d * d + k * k));
}

float sdCapsule(vec2 pLocal, vec2 halfSize, inout float zoneCap, inout float transitionPixels) {
    bool isVertical = halfSize.x < halfSize.y;
    vec2 hs = isVertical ? halfSize.yx : halfSize;
    vec2 pl = isVertical ? pLocal.yx : pLocal;
    vec2 q = vec2(smoothAbs(pl.x, kAbs), smoothAbs(pl.y, kAbs));
    float r = hs.y;
    float a = max(hs.x - r, 0.0);
    zoneCap = abs(pl.x) - a;
    float scale = r * 0.0125;
    float kMaxEff = kMax * scale;
    float xBandEff = junctionXBand * scale;
    float yBandEff = junctionYBand * scale;
    transitionPixels = max(DEFAULT_TRANSITION_PIXELS, xBandEff);
    float s = q.x - a;
    float xHard = max(s, 0.0);
    float xSoft = kMaxEff > 0.0 ? smoothMaxSqrt(s, 0.0, kMaxEff) : xHard;
    float wx = 1.0 - smoothstep(0.0, max(xBandEff, 1e-3), abs(s));
    float wy = smoothstep(r - max(yBandEff, 1e-3), r, q.y);
    float w = clamp(wx * wy, 0.0, 1.0);
    float x = mix(xHard, xSoft, w);
    return length(vec2(x, q.y)) - r;
}

float powFast(float x, float n) {
    x = max(x, 1e-8);
    return exp2(log2(x) * n);
}

float lpNorm(vec2 v, float n) {
    v = abs(v);
    float m = max(v.x, v.y);
    if (m < 1e-8) return 0.0;
    vec2 u = v / m;
    float s = powFast(u.x, n) + powFast(u.y, n);
    return m * exp2(log2(max(s, EPS)) / n);
}

float lpNorm4(vec2 v) {
    v = abs(v);
    vec2 v2 = v * v;
    vec2 v4 = v2 * v2;
    return sqrt(sqrt(max(v4.x + v4.y, 0.0)));
}

float sdRoundRect(vec2 p, vec2 halfSize, float r, float n, inout vec2 q) {
    q = abs(p) - halfSize + vec2(r);
    float nClamped = max(n, 2.0);
    float dCorner = abs(nClamped - 4.0) < 1e-3
        ? lpNorm4(max(q, 0.0))
        : lpNorm(max(q, 0.0), nClamped);
    if (q.x > 0.0 && q.y > 0.0) return dCorner - r;
    return max(q.x, q.y) - r;
}

float smoothSDF_D(vec2 p, vec2 b, float corner, float weight, inout float aaFactor) {
    float rectSdf = 0.0;
    float dCornerZone = 0.0;
    vec2 qRect = vec2(0.0);
    float transitionPixels = DEFAULT_TRANSITION_PIXELS;
    if (weight < 2.0) {
        float zoneCap = 0.0;
        if (weight > 1.005) {
            float t = smoothstep(2.0, 1.0, weight);
            float dG2 = sdRoundRect(p, b, corner, 2.0, qRect);
            float zoneG2 = min(qRect.x, qRect.y);
            float dCapsule = sdCapsule(p, b, zoneCap, transitionPixels);
            rectSdf = mix(dG2, dCapsule, t);
            dCornerZone = mix(zoneG2, zoneCap, t);
        } else {
            rectSdf = sdCapsule(p, b, zoneCap, transitionPixels);
            dCornerZone = zoneCap;
        }
    } else {
        rectSdf = sdRoundRect(p, b, corner, weight, qRect);
        dCornerZone = min(qRect.x, qRect.y);
    }
    aaFactor = smoothstep(-transitionPixels - 2.0, -transitionPixels * 0.2, dCornerZone);
    return rectSdf;
}

vec4 blendShadowColor(CommonDataRect rectData, vec4 color, float d, vec2 fragCoord) {
    vec3 fadeColorRgb = vec3(u_shadowArray[0], u_shadowArray[1], u_shadowArray[2]);
    vec3 fadeAlphaParams = vec3(u_shadowArray[3], u_shadowArray[4], u_shadowArray[5]);
    float fadeIn = u_shadowArray[6];
    vec2 fadeOffset = vec2(u_shadowArray[7], u_shadowArray[8]);
    vec2 fadeScale = vec2(u_shadowArray[9], u_shadowArray[10]);
    float fadeInPara1 = u_shadowArray[11];
    float fadeInPara2 = u_shadowArray[12];
    float fadeAlpha = 0.0;
    if (any(greaterThan(fadeAlphaParams.xy, vec2(EFFECT_PARAM_THRESHOLD)))) {
        vec2 posNew = rectData.pos - fadeOffset;
        float fadeCorner = getQuadrantValue(
            posNew,
            vec4(u_commonArray[6], u_commonArray[7], u_commonArray[8], u_commonArray[9])
        );
        float fadeLine = -fadeIn;
        float fadeSdf = fadeLine;
        vec2 halfSizeFade = rectData.halfSize * fadeScale;
        fadeCorner = min(min(halfSizeFade.x, halfSizeFade.y), max(fadeIn * 1.2, fadeCorner));
        if (rectData.weight < 2.0 || any(greaterThan(abs(posNew), halfSizeFade - vec2(fadeCorner)))) {
            float shadowAaFactor = 1.0;
            fadeSdf = smoothSDF_D(posNew, halfSizeFade, fadeCorner, rectData.weight, shadowAaFactor);
            float alpha = smoothstep(fadeLine, 0.0, fadeSdf);
            if (fadeInPara2 > EFFECT_PARAM_THRESHOLD) {
                alpha = pow(alpha, fadeInPara1) * fadeInPara2 + (1.0 - fadeInPara2) * alpha;
            }
            fadeAlpha = alpha * (fadeAlphaParams.x - fadeAlphaParams.y) + fadeAlphaParams.y;
        } else {
            fadeAlpha = fadeAlphaParams.y;
        }
    }
    vec2 uvPos = fragCoord.xy * 0.013;
    float n = clamp((noise(uvPos) - 0.5) * 0.01 + 0.5, 0.0, 1.0);
    fadeAlpha = clamp(softLight(n, fadeAlpha), 0.0, 1.0);
    vec4 fadeColor = vec4(fadeColorRgb, fadeAlpha);
    fadeColor.rgb *= fadeColor.a;
    vec4 result = vec4(0.0);
    result.rgb = color.rgb * (1.0 - fadeColor.a) + fadeColor.rgb;
    result.a = color.a * (1.0 - fadeColor.a) + fadeColor.a;
    return result;
}

float getDis(vec2 pos, vec2 halfsize) {
    vec2 rect = abs(pos - halfsize);
    return rect.x + rect.y;
}

vec4 blendEdge(CommonDataRect rectData, vec4 color, float d) {
    vec3 lineColorRgb = vec3(u_edgeArray[0], u_edgeArray[1], u_edgeArray[2]);
    vec2 lineNear = vec2(u_edgeArray[3], u_edgeArray[4]);
    vec2 lineFar = vec2(u_edgeArray[5], u_edgeArray[6]);
    vec2 lineAlphaParams = vec2(u_edgeArray[7], u_edgeArray[8]);
    vec4 lineFade = vec4(u_edgeArray[9], u_edgeArray[10], u_edgeArray[11], u_edgeArray[12]);
    float angle = u_edgeArray[13];
    float para1 = u_edgeArray[14];
    float para2 = u_edgeArray[15];
    if (d < -max(lineNear.y, lineFar.y)) return color;
    float distanceAll = (rectData.halfSize.x + rectData.halfSize.y) * 2.0;
    vec2 halfSize = rectData.halfSize;
    float ratio = mod(angle + rectData.halfSize.x / distanceAll * 0.5 + 0.5, 1.0);
    vec2 dirPos = normalize(rectData.pos);
    vec2 dirAbsPos = abs(dirPos);
    vec2 posN = min(
        vec2(dirAbsPos.xy * halfSize.yx / max(vec2(EFFECT_PARAM_THRESHOLD), dirAbsPos.yx)),
        halfSize
    ) * sign(dirPos);
    vec2 halfSizeSymbol = vec2(-halfSize.y, halfSize.x);
    float flag = mix(-1.0, 1.0, step(ratio, 0.5)) * dot(halfSizeSymbol, posN);
    float disPoint = distanceAll * min(ratio, 1.0 - ratio) * 2.0;
    float disPos = getDis(posN, halfSize);
    float distanceNear = mix(disPoint + disPos, abs(disPoint - disPos), step(0.0, flag));
    distanceNear = min(2.0 * distanceAll - distanceNear, distanceNear);
    float nearAlpha = lineAlphaParams.x * smoothstep(
        lineFade.y * distanceAll,
        lineFade.x * distanceAll,
        distanceNear
    );
    float farAlpha = lineAlphaParams.y * smoothstep(
        lineFade.w * distanceAll,
        lineFade.z * distanceAll,
        distanceAll - distanceNear
    );
    float nearSdf = smoothstep(-lineNear.y, -lineNear.x, d);
    float farSdf = smoothstep(-lineFar.y, -lineFar.x, d);
    if (para2 > EFFECT_PARAM_THRESHOLD) {
        nearSdf = pow(nearSdf, para1) * para2 + (1.0 - para2) * nearSdf;
        farSdf = pow(farSdf, para1) * para2 + (1.0 - para2) * farSdf;
    }
    float lineAlpha = clamp(nearAlpha * nearSdf + farAlpha * farSdf, 0.0, 1.0);
    vec4 lineColor = vec4(lineColorRgb, lineAlpha);
    lineColor.rgb *= lineColor.a;
    vec4 result = vec4(0.0);
    result.rgb = color.rgb * (1.0 - lineColor.a) + lineColor.rgb;
    result.a = color.a * (1.0 - lineColor.a) + lineColor.a;
    return result;
}

vec4 main(vec2 fragCoord) {
    float antiAliasing = u_commonArray[11];
    CommonDataRect rectData;
    rectData.halfResolution = vec2(u_commonArray[0], u_commonArray[1]) * 0.5;
    rectData.pos = vec2(fragCoord.x, u_commonArray[1] - fragCoord.y)
        - vec2(u_commonArray[2], u_commonArray[3])
        - rectData.halfResolution;
    rectData.halfSize = vec2(u_commonArray[4], u_commonArray[5]) * 0.5;
    vec2 s = step(vec2(0.0), rectData.pos);
    float rTop = mix(u_commonArray[6], u_commonArray[7], s.x);
    float rBot = mix(u_commonArray[9], u_commonArray[8], s.x);
    rectData.coner = mix(rTop, rBot, s.y);
    rectData.weight = u_commonArray[10];
    rectData.preCalcMinC = min(rectData.halfSize.x, rectData.halfSize.y);
    float aaFactor = 1.0;
    float d = smoothSDF_D(
        rectData.pos,
        rectData.halfSize,
        rectData.coner,
        rectData.weight,
        aaFactor
    );
    float aa = mix(EPS, 1.0, aaFactor);
    float shape = 1.0 - smoothstep(-aa, aa, d);
    if (shape < EFFECT_PARAM_THRESHOLD) return vec4(0.0);
    vec4 color = vec4(0.0);
    if (u_shadow > 0.5) color = blendShadowColor(rectData, color, d, fragCoord);
    if (u_edge > 0.5) color = blendEdge(rectData, color, d);
    return color * shape;
}
"""
