#version 460 core

layout(location = 0) in vec3  aPosition;
layout(location = 1) in vec3  aNormal;
layout(location = 2) in vec2  aTexCoord;
layout(location = 3) in float aBoneIndex;

struct EntityInstance {
    float posX, posY, posZ;
    float rotationY;
    float limbSwing;
    float limbSwingAmount;
    float headYaw;
    float headPitch;
    float attackProgress;
    float bowPullProgress;
    float hurtTime;
    float deathTime;
    float sneakProgress;
    float swimProgress;
    int   flags;
    float riptideProgress;
    float sitProgress;
    float eatProgress;
    float swellAmount;
    float explodeProgress;
    float rollProgress;
    int   entityTypeIndex;
    int   animationCategory;
    int   textureArrayLayer;
    int   heldItemMain, heldItemOffhand;
    int   armorHead, armorChest, armorLegs, armorFeet;
    int   mountEntityID;
    float seatOffsetX, seatOffsetY, seatOffsetZ;
    float texScaleX, texScaleY;
    float padding1, padding2, padding3, padding4;
};
layout(std430, binding = 12) buffer EntityInstanceBuffer { EntityInstance instances[]; };

uniform mat4 uViewProjection;
uniform float uGameTime;
uniform int  uBaseInstance;

out vec2      vTexCoord;
out flat int  vFlags;
out vec3      vNormal;
out float     vHurtAlpha;
out float     vGlintAnim;

// ── Animation categories ──────────────────────────────────────────────────────
#define ANIM_BIPED             0
#define ANIM_QUADRUPED         1
#define ANIM_HORSE             2
#define ANIM_BIRD              3
#define ANIM_ARTHROPOD         4
#define ANIM_INSECT            5
#define ANIM_WORM              6
#define ANIM_FISH              7
#define ANIM_AQUATIC_LEGS      8
#define ANIM_SWIMMING          9
#define ANIM_SLIME             10
#define ANIM_FLOATING          11
#define ANIM_FLOATING_SPINNING 12
#define ANIM_GHAST             13
#define ANIM_SHULKER           14
#define ANIM_STRIDER           15
#define ANIM_FROG              16
#define ANIM_GOAT              17
#define ANIM_SNIFFER           18
#define ANIM_ARMADILLO         19
#define ANIM_CREEPER           20

#define BONE_HEAD   0
#define BONE_BODY   1
#define BONE_ARM_L  2
#define BONE_ARM_R  3
#define BONE_LEG_L  4
#define BONE_LEG_R  5
#define BONE_LEG_FL 2
#define BONE_LEG_FR 3
#define BONE_LEG_BL 4
#define BONE_LEG_BR 5

#define FLAG_IN_WATER    8
#define FLAG_ZOMBIE_ARMS 256

#define PI 3.14159265358979

// ── Matrix helpers ────────────────────────────────────────────────────────────
mat4 rotX(float a){float c=cos(a),s=sin(a);return mat4(1,0,0,0,0,c,s,0,0,-s,c,0,0,0,0,1);}
mat4 rotY(float a){float c=cos(a),s=sin(a);return mat4(c,0,-s,0,0,1,0,0,s,0,c,0,0,0,0,1);}
mat4 rotZ(float a){float c=cos(a),s=sin(a);return mat4(c,s,0,0,-s,c,0,0,0,0,1,0,0,0,0,1);}
mat4 transl(vec3 t){return mat4(1,0,0,0,0,1,0,0,0,0,1,0,t.x,t.y,t.z,1);}
mat4 scaleMat(vec3 s){return mat4(s.x,0,0,0,0,s.y,0,0,0,0,s.z,0,0,0,0,1);}

// ── Pivot helpers ─────────────────────────────────────────────────────────────
// ── Bone pivots — hardcoded in shader pixel space (Y-up, feet=0, 1unit=1pixel) ──
// Verified space: scale(16,-16,16) + translate(0,-1.5,0) applied during bake.
// arm_l (5/16, 2/16, 0) → (5, 22, 0). head neck → (0, 24, 0). feet → y=0.
vec3 getP(int cat, int b) {
    // BIPED: zombie, skeleton, husk, drowned, villager, pillager, wither skeleton...
    if (cat == ANIM_BIPED || cat == ANIM_GOAT) {
        if (b == BONE_HEAD)  return vec3( 0.0, 24.0,  0.0);
        if (b == BONE_BODY)  return vec3( 0.0, 24.0,  0.0);
        if (b == BONE_ARM_L) return vec3( 5.0, 22.0,  0.0);
        if (b == BONE_ARM_R) return vec3(-5.0, 22.0,  0.0);
        if (b == BONE_LEG_L) return vec3( 2.0, 12.0,  0.0);
        if (b == BONE_LEG_R) return vec3(-2.0, 12.0,  0.0);
    }
    // QUADRUPED: cow, pig, sheep, wolf, fox...
    if (cat == ANIM_QUADRUPED || cat == ANIM_HORSE ||
        cat == ANIM_SNIFFER   || cat == ANIM_ARMADILLO) {
        if (b == BONE_HEAD)   return vec3( 0.0, 20.0,  8.0);
        if (b == BONE_BODY)   return vec3( 0.0, 20.0,  2.0);
        if (b == BONE_LEG_FL) return vec3(-3.0, 12.0,  7.0);
        if (b == BONE_LEG_FR) return vec3( 3.0, 12.0,  7.0);
        if (b == BONE_LEG_BL) return vec3(-3.0, 12.0, -5.0);
        if (b == BONE_LEG_BR) return vec3( 3.0, 12.0, -5.0);
    }
    // CREEPER: 4 legs
    if (cat == ANIM_CREEPER) {
        if (b == BONE_HEAD)  return vec3( 0.0, 18.0,  0.0);
        if (b == BONE_BODY)  return vec3( 0.0, 18.0,  0.0);
        if (b == BONE_LEG_L) return vec3( 2.0,  6.0,  4.0);
        if (b == BONE_LEG_R) return vec3(-2.0,  6.0,  4.0);
        if (b == BONE_LEG_BL)return vec3( 2.0,  6.0, -4.0);
        if (b == BONE_LEG_BR)return vec3(-2.0,  6.0, -4.0);
    }
    // BIRD: chicken, parrot
    if (cat == ANIM_BIRD) {
        if (b == BONE_HEAD)  return vec3( 0.0, 15.0,  0.0);
        if (b == BONE_ARM_L) return vec3( 4.0, 13.0,  0.0);
        if (b == BONE_ARM_R) return vec3(-4.0, 13.0,  0.0);
        if (b == BONE_LEG_L) return vec3( 2.0,  5.0,  0.0);
        if (b == BONE_LEG_R) return vec3(-2.0,  5.0,  0.0);
    }
    // STRIDER
    if (cat == ANIM_STRIDER) {
        if (b == BONE_HEAD)  return vec3( 0.0, 19.0,  0.0);
        if (b == BONE_LEG_L) return vec3( 4.0,  8.0,  0.0);
        if (b == BONE_LEG_R) return vec3(-4.0,  8.0,  0.0);
    }
    // FROG
    if (cat == ANIM_FROG) {
        if (b == BONE_HEAD)  return vec3( 0.0, 10.0,  1.0);
        if (b == BONE_LEG_L) return vec3( 3.0,  4.0, -3.0);
        if (b == BONE_LEG_R) return vec3(-3.0,  4.0, -3.0);
    }
    // ARTHROPOD: spider
    if (cat == ANIM_ARTHROPOD) {
        if (b == BONE_HEAD) return vec3( 0.0,  9.0,  4.0);
        if (b == BONE_BODY) return vec3( 0.0,  9.0, -3.0);
        float side = (mod(float(b), 2.0) == 0.0) ? 4.0 : -4.0;
        return vec3(side, 8.0, 3.5 - float(b-2)*1.5);
    }
    // INSECT: bee
    if (cat == ANIM_INSECT) {
        if (b == BONE_HEAD)  return vec3( 0.0, 10.0,  3.0);
        if (b == BONE_ARM_L) return vec3( 3.0,  9.0,  0.0);
        if (b == BONE_ARM_R) return vec3(-3.0,  9.0,  0.0);
    }
    // SHULKER
    if (cat == ANIM_SHULKER) {
        if (b == BONE_HEAD) return vec3(0.0, 8.0, 0.0);
    }
    // Default — rotate around origin
    return vec3(0.0);
}
// Rotate around 'p' by rotation matrix 'r': T(p) * r * T(-p)
mat4 pivotRot(vec3 p, mat4 r){ return transl(p) * r * transl(-p); }

// --- BIPED ---
mat4 getBipedBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float w1=sa>0.01?sin(sw*0.6662)*1.4*sa:0.0;
    float w2=sa>0.01?sin(sw*0.6662+PI)*1.4*sa:0.0;
    vec3 p=getP(inst.animationCategory,b);

    if(b==BONE_HEAD){
        mat4 r=rotY(inst.headYaw)*rotX(inst.headPitch);
        if(inst.sneakProgress>0.0) r=rotX(0.5)*r;
        return pivotRot(p,r);
    }
    if(b==BONE_BODY){
        vec3 sp=vec3(p.x,p.y-12.0,p.z); // mid-torso sneak/death pivot
        mat4 m=mat4(1.0);
        if(inst.sneakProgress>0.0) m=pivotRot(sp,rotX(0.5));
        if(inst.deathTime>0.0){float t=min(inst.deathTime/20.0,1.0);m=pivotRot(sp,rotZ(t*PI*0.5))*m;}
        return m;
    }
    if(b==BONE_ARM_L){
        float tilt=((inst.flags&FLAG_ZOMBIE_ARMS)!=0)?-PI*0.5:0.0;
        float sway=0.0;
        return pivotRot(p,rotX(w2+tilt)*rotZ(0.05+sway));
    }
    if(b==BONE_ARM_R){
        float tilt=((inst.flags&FLAG_ZOMBIE_ARMS)!=0)?-PI*0.5:0.0;
        float sway=0.0;
        return pivotRot(p,rotX(w1+tilt)*rotZ(-(0.05+sway)));
    }
    if(b==BONE_LEG_L) return pivotRot(p,rotX(w1));
    if(b==BONE_LEG_R) return pivotRot(p,rotX(w2));
    return mat4(1.0);
}

// --- QUADRUPED ---
mat4 getQuadBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD){
        mat4 r=rotX(inst.headPitch)*rotY(inst.headYaw);
        if(inst.eatProgress>0.0) r=rotX(inst.eatProgress*0.7854)*r;
        return pivotRot(p,r);
    }
    if(b==BONE_BODY){
        float bob=sin(sw*0.3)*sa*0.1;
        mat4 m=transl(vec3(0,bob,0));
        if(inst.sitProgress>0.0) m=pivotRot(p,rotX(inst.sitProgress*0.5))*m;
        return m;
    }
    float phase=(b==BONE_LEG_FL||b==BONE_LEG_BL)?0.0:PI;
    if(b==BONE_LEG_BL||b==BONE_LEG_BR) phase+=PI*0.5;
    return pivotRot(p,rotX(sin(sw*0.6662+phase)*sa));
}

// --- HORSE ---
mat4 getHorseBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD){
        float nod=sin(sw*0.8)*sa*0.3;
        mat4 r=rotX(nod+inst.headPitch)*rotY(inst.headYaw);
        if(inst.eatProgress>0.0) r=rotX(inst.eatProgress)*r;
        return pivotRot(p,r);
    }
    if(b==BONE_BODY){
        if(inst.sitProgress>0.0) return transl(vec3(0,-inst.sitProgress*5.0,0));
        return mat4(1.0);
    }
    float phase=(b==BONE_LEG_FL||b==BONE_LEG_BR)?0.0:PI;
    float lr=sin(sw*0.8+phase)*sa*1.4;
    if(b==BONE_LEG_FL||b==BONE_LEG_FR) lr+=max(0.0,sa-1.0)*0.8;
    return pivotRot(p,rotX(lr));
}

// --- BIRD ---
mat4 getBirdBone(int b, EntityInstance inst){
    float t=uGameTime*0.1;
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD) return pivotRot(p,rotX(sin(sw*0.6662)*sa*0.5+inst.headPitch));
    if(b==BONE_ARM_L){float f=sa<0.01?sin(t*2.0)*0.1:sin(t*5.0)*0.4+0.3; return pivotRot(p,rotZ(-f));}
    if(b==BONE_ARM_R){float f=sa<0.01?sin(t*2.0)*0.1:sin(t*5.0)*0.4+0.3; return pivotRot(p,rotZ(f));}
    if(b==BONE_LEG_L) return pivotRot(p,rotX(sin(sw*0.6662)*sa*0.8));
    if(b==BONE_LEG_R) return pivotRot(p,rotX(sin(sw*0.6662+PI)*sa*0.8));
    return mat4(1.0);
}

// --- ARTHROPOD ---
mat4 getArthropodBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float t=uGameTime*0.15;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD) return pivotRot(p,rotX(inst.headPitch));
    int pair=b-2;
    float ph=float(pair)*0.7854+(pair%2==0?0.0:PI);
    return pivotRot(p,rotX(sin(sw*1.2+ph)*sa*0.8+sin(t+ph)*0.3));
}

// --- INSECT ---
mat4 getInsectBone(int b, EntityInstance inst){
    float t=uGameTime*0.1;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD) return pivotRot(p,rotX(inst.headPitch*0.5));
    if(b==BONE_ARM_L){float sp=inst.swimProgress>0.0?15.0:8.0; return pivotRot(p,rotY(-sin(t*sp)*0.8)*rotZ(-0.4));}
    if(b==BONE_ARM_R){float sp=inst.swimProgress>0.0?15.0:8.0; return pivotRot(p,rotY(sin(t*sp)*0.8)*rotZ(0.4));}
    float ph=float(b-BONE_LEG_L)*1.0472;
    return pivotRot(p,rotX(sin(inst.limbSwing*1.5+ph)*inst.limbSwingAmount*0.6));
}

// --- WORM ---(vertex-position wave — pure translation, normals unchanged) ─────────
mat4 getWormBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float t=uGameTime*0.1;
    float ph=aPosition.z*0.3+sw;
    float xOff=sin(ph)*sa*0.4+sin(t+aPosition.z*0.5)*0.1;
    float yOff=abs(sin(ph*2.0))*sa*0.1;
    return transl(vec3(xOff,yOff,0));
}

// --- FISH ---
mat4 getFishBone(int b, EntityInstance inst){
    float t=uGameTime*0.1;
    vec3 p=getP(inst.animationCategory,b);
    mat4 bodyTilt=pivotRot(p,rotY(inst.headYaw*0.3));
    if(b>=1){
        float tailAmp=(1.0-aPosition.z*0.05)*(0.3+inst.limbSwingAmount*0.5);
        float xOff=sin(aPosition.z*0.4+t*3.0)*tailAmp;
        return transl(vec3(xOff,0,0))*bodyTilt;
    }
    return bodyTilt;
}

// --- AQUATIC_LEGS ---
mat4 getAquaticLegsBone(int b, EntityInstance inst){
    bool inWater=(inst.flags&FLAG_IN_WATER)!=0;
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float t=uGameTime*0.1;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD) return pivotRot(p,rotX(inst.headPitch*0.5));
    if(inWater){
        float tw=sin(aPosition.z*0.3+t*4.0)*sa*0.6;
        return transl(vec3(tw,0,0))*pivotRot(p,rotX(sin(t*2.0)*0.1));
    }
    float ph=(b==BONE_LEG_FL||b==BONE_LEG_BL)?0.0:PI;
    if(b==BONE_LEG_BL||b==BONE_LEG_BR) ph+=PI*0.5;
    return pivotRot(p,rotX(sin(sw*0.6662+ph)*sa));
}

// --- SWIMMING ---
mat4 getSwimmingBone(int b, EntityInstance inst){
    float t=uGameTime*0.1;
    float sa=inst.limbSwingAmount;
    if(b>=2) return transl(vec3(sin(float(b)*0.7854+t*4.0)*0.4,0,0));
    float xOff=sin(aPosition.z*0.2+t*3.0)*(0.5+sa*0.8);
    return transl(vec3(xOff,sin(t*1.5)*0.2,0));
}

// --- SLIME ---
mat4 getSlimeBone(int b, EntityInstance inst){
    float t=uGameTime*0.1;
    float sa=inst.limbSwingAmount;
    float bounce=abs(sin(t*3.0+inst.limbSwing));
    return scaleMat(vec3(1.0+bounce*0.3*sa, 1.0-bounce*0.3*sa, 1.0+bounce*0.3*sa));
}

// --- FLOATING ---
mat4 getFloatingBone(int b, EntityInstance inst){
    float t=uGameTime*0.05;
    float bob=sin(t*2.0)*0.5;
    mat4 base=transl(vec3(0,bob,0))*pivotRot(vec3(0),rotZ(sin(t*1.5)*0.1));
    vec3 p=getP(inst.animationCategory,b);
    if(b>=BONE_ARM_L){
        float side=(b==BONE_ARM_L)?-1.0:1.0;
        return base*pivotRot(p,rotZ(side*sin(uGameTime*0.2*6.0)*0.5));
    }
    return base;
}

// --- FLOATING_SPINNING ---
mat4 getFloatingSpinningBone(int b, EntityInstance inst){
    float t=uGameTime*0.05;
    mat4 base=transl(vec3(0,sin(t*2.5)*0.5,0));
    if(b==BONE_BODY) return base;
    return base*rotY(t*5.0+float(b-2)*0.5236);
}

// --- GHAST ---
mat4 getGhastBone(int b, EntityInstance inst){
    float t=uGameTime*0.05;
    if(b<=BONE_HEAD) return transl(vec3(0,sin(t*1.5)*0.3,0));
    float ph=float(b-2)*0.6981;
    float tipF=1.0-(aPosition.y/16.0);
    return transl(vec3(sin(t*3.0+ph)*0.8*tipF, 0, cos(t*2.5+ph*1.3)*0.6*tipF));
}

// --- SHULKER ---
mat4 getShulkerBone(int b, EntityInstance inst){
    if(b==BONE_HEAD)
        return transl(vec3(0,inst.attackProgress*6.0,0))*pivotRot(vec3(0),rotX(inst.attackProgress*0.2));
    return mat4(1.0);
}

// --- STRIDER ---
mat4 getStriderBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD||b==BONE_BODY) return transl(vec3(sin(sw*0.5)*sa*0.3,0,0));
    if(b==BONE_LEG_L) return pivotRot(p,rotX(sin(sw*0.6662)*sa*1.2));
    if(b==BONE_LEG_R) return pivotRot(p,rotX(sin(sw*0.6662+PI)*sa*1.2));
    return mat4(1.0);
}

// --- FROG ---
mat4 getFrogBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float crouch=abs(sin(sw*0.4))*sa;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD) return pivotRot(p,rotX(inst.headPitch+crouch*0.3));
    if(b==BONE_BODY) return scaleMat(vec3(1.0+crouch*0.15,1.0-crouch*0.2,1.0+crouch*0.15));
    float ph=(b==BONE_LEG_L)?0.0:PI;
    return pivotRot(p,rotX(-crouch*1.2+sin(sw*0.6662+ph)*sa*0.8)*rotZ(crouch*0.5));
}

// --- GOAT ---
mat4 getGoatBone(int b, EntityInstance inst){
    mat4 base=getQuadBone(b,inst);
    if(b==BONE_HEAD&&inst.attackProgress>0.0)
        return pivotRot(getP(inst.animationCategory,b),rotX(inst.attackProgress*1.0472));
    return base;
}

// --- SNIFFER ---
mat4 getSnifferBone(int b, EntityInstance inst){
    mat4 base=getQuadBone(b,inst);
    if(b==BONE_HEAD&&inst.eatProgress>0.0)
        return pivotRot(getP(inst.animationCategory,b),rotX(sin(inst.eatProgress*PI)*1.2));
    return base;
}

// --- ARMADILLO ---
mat4 getArmadilloBone(int b, EntityInstance inst){
    if(inst.rollProgress>0.0){
        float roll=inst.rollProgress;
        vec3 center=vec3(0,8,0);
        // Lerp vertices toward center (limbs tuck under shell)
        vec3 curlOff=mix(aPosition,center+(aPosition-center)*0.1,roll)-aPosition;
        float ss=1.0-roll*0.15;
        return transl(curlOff)*transl(center)*scaleMat(vec3(ss))*transl(-center);
    }
    return getQuadBone(b,inst);
}

// --- CREEPER ---
mat4 getCreeperBone(int b, EntityInstance inst){
    float sw=inst.limbSwing, sa=inst.limbSwingAmount;
    float swell=inst.swellAmount;
    vec3 p=getP(inst.animationCategory,b);
    if(b==BONE_HEAD){
        if(swell>0.0){float ss=1.0+swell*0.2; return transl(p)*scaleMat(vec3(ss))*transl(-p);}
        return mat4(1.0);
    }
    if(b==BONE_BODY){
        if(swell>0.0){float ss=1.0+swell*0.25; return transl(p)*scaleMat(vec3(ss,1,ss))*transl(-p);}
        return mat4(1.0);
    }
    float ph=(b==BONE_LEG_L||b==BONE_LEG_BR)?0.0:PI;
    return pivotRot(p,rotX(sin(sw*0.6662+ph)*sa));
}

// ── Unified dispatch ──────────────────────────────────────────────────────────
mat4 getBoneTransform(int cat, int bone, EntityInstance inst){
    if(cat==ANIM_BIPED)             return getBipedBone(bone,inst);
    if(cat==ANIM_QUADRUPED)         return getQuadBone(bone,inst);
    if(cat==ANIM_HORSE)             return getHorseBone(bone,inst);
    if(cat==ANIM_BIRD)              return getBirdBone(bone,inst);
    if(cat==ANIM_ARTHROPOD)         return getArthropodBone(bone,inst);
    if(cat==ANIM_INSECT)            return getInsectBone(bone,inst);
    if(cat==ANIM_WORM)              return getWormBone(bone,inst);
    if(cat==ANIM_FISH)              return getFishBone(bone,inst);
    if(cat==ANIM_AQUATIC_LEGS)      return getAquaticLegsBone(bone,inst);
    if(cat==ANIM_SWIMMING)          return getSwimmingBone(bone,inst);
    if(cat==ANIM_SLIME)             return getSlimeBone(bone,inst);
    if(cat==ANIM_FLOATING)          return getFloatingBone(bone,inst);
    if(cat==ANIM_FLOATING_SPINNING) return getFloatingSpinningBone(bone,inst);
    if(cat==ANIM_GHAST)             return getGhastBone(bone,inst);
    if(cat==ANIM_SHULKER)           return getShulkerBone(bone,inst);
    if(cat==ANIM_STRIDER)           return getStriderBone(bone,inst);
    if(cat==ANIM_FROG)              return getFrogBone(bone,inst);
    if(cat==ANIM_GOAT)              return getGoatBone(bone,inst);
    if(cat==ANIM_SNIFFER)           return getSnifferBone(bone,inst);
    if(cat==ANIM_ARMADILLO)         return getArmadilloBone(bone,inst);
    if(cat==ANIM_CREEPER)           return getCreeperBone(bone,inst);
    return mat4(1.0);
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN
// ─────────────────────────────────────────────────────────────────────────────
void main(){
    int instanceID = gl_InstanceID + uBaseInstance;
    EntityInstance inst = instances[instanceID];
    int bone = int(aBoneIndex);

    // Per-bone transform — applied to both position and normal
    mat4 bt = getBoneTransform(inst.animationCategory, bone, inst);
    vec3 localPos = (bt * vec4(aPosition, 1.0)).xyz;
    vec3 localNrm = normalize((bt * vec4(aNormal, 0.0)).xyz);

    // Entity yaw rotation: vanilla applies Ry(180-bodyYaw).
    // Our Ry(θ) convention means θ = yaw_rad - PI, stored in rotationY.
    float s = sin(inst.rotationY), c = cos(inst.rotationY);
    vec3 rotPos = vec3(localPos.x*c - localPos.z*s, localPos.y, localPos.x*s + localPos.z*c);
    vec3 rotNrm = vec3(localNrm.x*c - localNrm.z*s, localNrm.y, localNrm.x*s + localNrm.z*c);

    // Pixels → blocks (×0.0625) + camera-relative world position
    vec4 worldPos = vec4(rotPos * 0.0625, 1.0);
    worldPos.x += inst.posX;
    worldPos.y += inst.posY;
    worldPos.z += inst.posZ;

    gl_Position = uViewProjection * worldPos;

    vTexCoord  = aTexCoord;
    vFlags     = inst.flags;
    vNormal    = rotNrm;
    vHurtAlpha = inst.hurtTime > 0.0 ? 0.7 : 0.0;
    vGlintAnim = fract(uGameTime * 0.001);
}
