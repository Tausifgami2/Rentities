#version 460 core
// Error fragment shader — glitchy magenta checkerboard + scanline noise

in  vec3  vLocalPos;
in  float vTime;
out vec4  fragColor;

#define PI 3.14159265

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    // Checkerboard in local space — alternates magenta / black
    float t    = vTime * 0.05;
    vec3  lp   = vLocalPos * 4.0 + t * 2.0; // UV scale + animate
    float check = mod(floor(lp.x) + floor(lp.y) + floor(lp.z), 2.0);

    // Base colors: magenta and near-black
    vec3 colA = vec3(1.0, 0.0, 1.0);   // magenta
    vec3 colB = vec3(0.05, 0.0, 0.05); // very dark

    vec3 col = mix(colB, colA, check);

    // Scanline noise — horizontal bars that shift over time
    float scanline = step(0.5, mod(vLocalPos.y * 12.0 + vTime * 3.0, 1.0));
    col = mix(col, col * 0.4, scanline * 0.5);

    // Random digital glitch pixels — flicker fast
    vec2 glitchUV = floor(vLocalPos.xy * 8.0 + vec2(floor(vTime * 15.0), 0.0));
    float glitch   = step(0.92, hash(glitchUV));
    col = mix(col, vec3(1.0, 1.0, 0.0), glitch); // yellow glitch pixels

    // Edge glow — highlight cube silhouette in bright white
    float edgeDist = min(
        min(abs(vLocalPos.x - 0.5), abs(vLocalPos.x + 0.5)),
        min(abs(vLocalPos.y - 0.5), abs(vLocalPos.y + 0.5))
    );
    float edge = 1.0 - smoothstep(0.0, 0.08, edgeDist);
    col = mix(col, vec3(1.0), edge);

    // Pulse alpha so it visibly throbs
    float pulse = 0.75 + 0.25 * sin(vTime * 0.2);
    fragColor = vec4(col, pulse);
}

