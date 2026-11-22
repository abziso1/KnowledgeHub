<script setup lang="ts">
import { computed } from 'vue'
import type { FolderView } from '../api/contracts'

const props = defineProps<{ folders: FolderView[]; selectedId?: string; loading: boolean }>()
defineEmits<{ select: [id?: string] }>()

interface FolderNode extends FolderView {
  children: FolderNode[]
}
const nodes = computed(() => {
  const children = new Map(props.folders.map(folder => [folder.id, { ...folder, children: [] } as FolderNode]))
  const roots: FolderNode[] = []
  for (const node of children.values()) {
    const parent = node.parentId ? children.get(node.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
})
</script>

<template>
  <section class="rail-section folder-rail" aria-label="文件夹">
    <div class="rail-heading"><h2>文件夹</h2></div>
    <button type="button" class="folder-root" :aria-current="selectedId ? undefined : 'page'" @click="$emit('select')">
      根目录
    </button>
    <p v-if="loading" class="muted" role="status">正在加载文件夹</p>
    <el-tree v-else :data="nodes" :props="{ label: 'name' }" node-key="id" highlight-current default-expand-all
             :current-node-key="selectedId" :expand-on-click-node="false" :indent="14" empty-text="暂无文件夹"
             @node-click="(node: FolderNode) => $emit('select', node.id)" />
  </section>
</template>
