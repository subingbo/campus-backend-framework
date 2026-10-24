<template>
  <el-dialog
    :model-value="modelValue"
    :title="schedule ? '编辑个人安排' : '新建个人安排'"
    width="min(460px, 94vw)"
    class="schedule-form-dialog ui-dialog"
    modal-class="ui-content-overlay"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <el-form label-width="72px" @submit.prevent>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="课程、会议、自习…" />
      </el-form-item>
      <el-form-item label="时间" required>
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          :clearable="false"
          style="width: 100%"
          popper-class="ui-content-popper"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="2"
          maxlength="256"
          show-word-limit
          placeholder="可选，仅自己可见"
        />
      </el-form-item>
      <p class="hint">个人安排只用于自己的日历与群忙闲计算，不会向群成员展示标题或备注。</p>
    </el-form>
    <template #footer>
      <button
        v-if="schedule"
        type="button"
        class="ui-btn ui-btn--danger"
        :disabled="removing || saving"
        :aria-busy="removing"
        @click="remove"
      >{{ removing ? '删除中…' : '删除' }}</button>
      <button type="button" class="ui-btn ui-btn--ghost" :disabled="saving || removing" @click="emit('update:modelValue', false)">取消</button>
      <button
        type="button"
        class="ui-btn ui-btn--primary"
        :disabled="saving || removing"
        :aria-busy="saving"
        @click="submit"
      >{{ saving ? '保存中…' : '保存' }}</button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scheduleApi } from '../api'
import { toDate, toLocalIso } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  schedule: { type: Object, default: null },
  defaultDate: { type: [Date, String, Array], default: null }
})
const emit = defineEmits(['update:modelValue', 'saved', 'deleted'])

const saving = ref(false)
const removing = ref(false)
const form = reactive({ title: '', range: [], note: '' })

function defaultRange() {
  const base = toDate(props.defaultDate) || new Date()
  const start = new Date(base.getFullYear(), base.getMonth(), base.getDate(), 9, 0, 0)
  // 选中的日期已过去时，默认放到下一个整点，避免一打开就是非法时间
  if (start.getTime() < Date.now() - 5 * 60 * 1000) {
    const next = new Date(Date.now() + 60 * 60 * 1000)
    next.setMinutes(0, 0, 0)
    return [next, new Date(next.getTime() + 60 * 60 * 1000)]
  }
  return [start, new Date(start.getTime() + 60 * 60 * 1000)]
}

function reset() {
  if (props.schedule) {
    form.title = props.schedule.title || ''
    form.range = [toDate(props.schedule.startTime), toDate(props.schedule.endTime)]
    form.note = props.schedule.note || ''
  } else {
    form.title = ''
    form.range = defaultRange()
    form.note = ''
  }
}

watch(
  () => [props.modelValue, props.schedule],
  () => {
    if (props.modelValue) reset()
  },
  { immediate: true }
)

async function submit() {
  if (saving.value) return
  const title = form.title.trim()
  if (!title) {
    ElMessage.warning('填一个标题')
    return
  }
  if (!form.range?.[0] || !form.range?.[1]) {
    ElMessage.warning('选择起止时间')
    return
  }
  const start = toDate(form.range[0])
  const end = toDate(form.range[1])
  if (!start || !end || end.getTime() <= start.getTime()) {
    ElMessage.warning('结束时间需晚于开始时间')
    return
  }
  saving.value = true
  try {
    const payload = {
      title,
      startTime: toLocalIso(start),
      endTime: toLocalIso(end),
      note: form.note.trim() || null
    }
    const saved = props.schedule
      ? await scheduleApi.update(props.schedule.id, payload)
      : await scheduleApi.create(payload)
    ElMessage.success(props.schedule ? '个人安排已更新' : '个人安排已添加')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!props.schedule || removing.value) return
  try {
    await ElMessageBox.confirm(`删除「${props.schedule.title}」？`, '删除个人安排', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  removing.value = true
  try {
    await scheduleApi.remove(props.schedule.id)
    ElMessage.success('已删除')
    emit('deleted', props.schedule)
    emit('update:modelValue', false)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  } finally {
    removing.value = false
  }
}
</script>

<style scoped>
.hint {
  margin: 0;
  color: var(--ui-ink-3);
  font-size: 12px;
  line-height: 1.5;
}
@media (max-width: 768px) {
  :global(.schedule-form-dialog .el-dialog__body) {
    max-height: 60vh;
    overflow: auto;
  }
  :global(.schedule-form-dialog .el-dialog__footer) {
    position: sticky;
    bottom: 0;
    background: var(--ui-surface);
    padding-top: 8px;
  }
}
</style>
