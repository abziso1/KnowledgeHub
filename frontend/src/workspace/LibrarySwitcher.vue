<script setup lang="ts">
import type { LibraryView } from '../api/contracts'

defineProps<{ libraries: LibraryView[]; selectedId?: string }>()
defineEmits<{ select: [id: string]; create: [] }>()
</script>

<template>
  <section class="rail-section" aria-label="知识库">
    <div class="rail-heading">
      <h2>知识库</h2>
      <el-button size="small" text @click="$emit('create')">新建知识库</el-button>
    </div>
    <ul v-if="libraries.length" class="library-index">
      <li v-for="library in libraries" :key="library.id">
        <button type="button" class="library-option" :class="{ selected: library.id === selectedId }"
                :aria-current="library.id === selectedId ? 'page' : undefined" @click="$emit('select', library.id)">
          {{ library.name }}
        </button>
      </li>
    </ul>
  </section>
</template>
