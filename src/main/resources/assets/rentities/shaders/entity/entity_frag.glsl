#version 460 core

in vec2      vTexCoord;
in flat int  vFlags;
in vec3      vNormal;
in float     vHurtAlpha;
in float     vGlintAnim;

out vec4 fragColor;

// Minecraft entity texture bound directly per draw call — no atlas
uniform sampler2D uEntityTexture;

#define FLAG_IS_INVISIBLE 64
#define FLAG_HAS_GLINT    4

void main() {
    if ((vFlags & FLAG_IS_INVISIBLE) != 0) discard;

    vec4 tex = texture(uEntityTexture, vTexCoord);

    // Discard fully transparent pixels (entity textures have alpha cutouts)
    if (tex.a < 0.05) discard;

    // Simple directional lighting
    vec3 N = normalize(length(vNormal) < 0.01 ? vec3(0.0, 1.0, 0.0) : vNormal);
    vec3 L = normalize(vec3(0.4, 1.0, 0.6));
    float light = 0.4 + max(0.0, dot(N, L)) * 0.6;

    vec3 color = tex.rgb * light;

    // Hurt flash (red overlay)
    if (vHurtAlpha > 0.0) {
        color = mix(color, vec3(1.0, 0.0, 0.0), vHurtAlpha);
    }

    // Enchantment glint
    if ((vFlags & FLAG_HAS_GLINT) != 0) {
        float glint = sin(vTexCoord.x * 20.0 + vGlintAnim) * 0.5 + 0.5;
        color += vec3(0.5, 0.2, 0.8) * glint * 0.3;
    }

    fragColor = vec4(color, tex.a);
}
