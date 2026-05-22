package me.balancinglight.rentities.entities;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

public class EntityMeshCapturingConsumer implements VertexConsumer {

    private final List<float[]> captured = new ArrayList<>();

    private float vx, vy, vz;
    private float vnx, vny, vnz;
    private float vu, vv;
    private int currentBone = 0;

    private float pivotX = 0, pivotY = 0, pivotZ = 0;
    private boolean hasPivot = false;

    public void setBone(int boneIndex) {
        this.currentBone = boneIndex;
    }

    public void setBonePivot(float px, float py, float pz) {
        this.pivotX = px;
        this.pivotY = py;
        this.pivotZ = pz;
        this.hasPivot = true;
    }

    public void clearBonePivot() {
        this.pivotX = 0;
        this.pivotY = 0;
        this.pivotZ = 0;
        this.hasPivot = false;
    }

    public int capturedVertexCount() {
        return captured.size();
    }

    public float[] bakeAndReset() {
        float[] result = new float[captured.size() * 9];
        int i = 0;
        for (float[] v : captured) {
            System.arraycopy(v, 0, result, i, 9);
            i += 9;
        }
        captured.clear();
        return result;
    }

    public void reset() {
        captured.clear();
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.vx = x;
        this.vy = y;
        this.vz = z;
        this.lastNormalMatrix.identity();
        return this;
    }

    @Override
    public VertexConsumer addVertex(com.mojang.blaze3d.vertex.PoseStack.Pose pose, float x, float y, float z) {
        org.joml.Vector4f pos = new org.joml.Vector4f(x, y, z, 1.0f).mul(pose.pose());
        this.vx = pos.x;
        this.vy = pos.y;
        this.vz = pos.z;
        this.lastNormalMatrix = pose.normal();
        return this;
    }

    private org.joml.Matrix3f lastNormalMatrix = new org.joml.Matrix3f();

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int packedArgb) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.vu = u;
        this.vv = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float nx, float ny, float nz) {
        org.joml.Vector3f norm = new org.joml.Vector3f(nx, ny, nz).mul(lastNormalMatrix);
        this.vnx = norm.x;
        this.vny = norm.y;
        this.vnz = norm.z;
        float fx = hasPivot ? vx - pivotX : vx;
        float fy = hasPivot ? vy - pivotY : vy;
        float fz = hasPivot ? vz - pivotZ : vz;
        captured.add(new float[]{fx, fy, fz, vnx, vny, vnz, vu, vv, currentBone});
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        float fx = hasPivot ? x - pivotX : x;
        float fy = hasPivot ? y - pivotY : y;
        float fz = hasPivot ? z - pivotZ : z;
        captured.add(new float[]{fx, fy, fz, nx, ny, nz, u, v, currentBone});
    }
}
