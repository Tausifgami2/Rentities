#version 460 core
// Error vertex shader — renders unscanned entities as a glitchy magenta cube

layout(location = 0) in vec3 aPosition;

struct EntityInstance {
    float posX, posY, posZ;
    float rotationY;
    float limbSwing, limbSwingAmount, headYaw, headPitch;
    float attackProgress, bowPullProgress, hurtTime, deathTime;
    float sneakProgress, swimProgress;
    int   flags;
    float riptideProgress, sitProgress, eatProgress, swellAmount;
    float explodeProgress, rollProgress;
    int   entityTypeIndex, animationCategory, textureArrayLayer;
    int   heldItemMain, heldItemOffhand;
    int   armorHead, armorChest, armorLegs, armorFeet;
    int   mountEntityID;
    float seatOffsetX, seatOffsetY, seatOffsetZ;
    float texScaleX, texScaleY;
    float padding1, padding2, padding3, padding4;
};
layout(std430, binding = 12) buffer EntityInstanceBuffer { EntityInstance instances[]; };

uniform mat4  uViewProjection;
uniform float uGameTime;
uniform int   uBaseInstance;

out vec3 vLocalPos;
out float vTime;

void main() {
    int idx = gl_InstanceID + uBaseInstance;
    EntityInstance inst = instances[idx];

    // Animate: bob up/down + slow spin to make it obvious
    float t   = uGameTime * 0.05;
    float bob = sin(t * 3.0) * 0.15;
    float spinY = t * 2.0;
    float sc = cos(spinY), ss = sin(spinY);

    // Error cube is 0.6 blocks wide centred at entity origin
    vec3 p = aPosition * 0.6;
    // Apply spin
    vec3 rotP = vec3(p.x*sc - p.z*ss, p.y + bob, p.x*ss + p.z*sc);

    vec4 world = vec4(rotP * 0.0625, 1.0);
    world.x += inst.posX;
    world.y += inst.posY + 0.9; // lift to roughly entity chest height
    world.z += inst.posZ;

    gl_Position = uViewProjection * world;
    vLocalPos   = aPosition;
    vTime       = uGameTime;
}

