# Rentities (early alpha)

GPU-instanced entity rendering for Minecraft **1.21.11** on **NVIDIA** GPUs (Turing+). Requires **Sodium 0.8.6**.

## Requirements

- NVIDIA GPU (GTX 16xx / RTX or newer)
- [Fabric Loader](https://fabricmc.net/) 0.17.2+
- [Sodium](https://modrinth.com/mod/sodium) 0.8.6 (hard dependency)
- **Lithium** is strongly recommended at high entity counts (CPU still builds render states per entity)

## What it does

Batches supported living entities into a single instanced draw using SSBOs and a texture array. Vanilla entity draws for registered types are skipped when batching is active.

## Alpha limitations

- NVIDIA-only; mod disables itself on other vendors
- Not all entity types are batchable; unsupported types use vanilla rendering
- First launch bakes meshes and builds caches under `.minecraft/` (`rentities_entity_mesh_cache.bin`, `rentities_entity_texture_cache.bin`)
- Very high entity counts can still be CPU-bound (render-state extraction)
- Scan / stress tests with everything on screen are worst-case; in-world frustum culling helps more in normal play


## License

MIT — see [LICENSE.txt](LICENSE.txt).
