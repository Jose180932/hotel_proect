<template>
  <div class="knowledge-page">
    <div class="page-header">
      <h2>知识库管理</h2>
      <div class="header-actions">
        <el-button :loading="rebuilding" type="warning" plain @click="handleRebuild">
          <el-icon><Refresh /></el-icon> 重建索引
        </el-button>
        <el-button :loading="evaluating" plain @click="handleEval">
          <el-icon><DataAnalysis /></el-icon> 检索评测
        </el-button>
      </div>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="4">
        <div class="stat-card"><div class="stat-value">{{ stats.documents ?? '-' }}</div><div class="stat-label">文档数</div></div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card"><div class="stat-value">{{ stats.chunks ?? '-' }}</div><div class="stat-label">切片数</div></div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card"><div class="stat-value">{{ stats.embeddedChunks ?? '-' }}</div><div class="stat-label">已向量化</div></div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card"><div class="stat-value">{{ stats.retrievalMode || '-' }}</div><div class="stat-label">检索模式</div></div>
      </el-col>
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-value small">{{ stats.embeddingModel || '-' }}</div>
          <div class="stat-label">
            向量模型
            <el-tag :type="stats.embeddingAvailable ? 'success' : 'info'" size="small" effect="plain">
              {{ stats.embeddingAvailable ? '已启用' : '未启用' }}
            </el-tag>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 上传 -->
    <el-card class="section-card" shadow="never">
      <template #header><span class="section-title">上传文档</span></template>
      <el-upload
        drag
        :show-file-list="false"
        :http-request="handleUpload"
        accept=".md,.markdown,.txt,.docx"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 .md / .txt / .docx，单文件不超过 10MB；上传后自动切块并向量化</div>
        </template>
      </el-upload>
    </el-card>

    <!-- 文档列表 -->
    <el-card class="section-card" shadow="never">
      <template #header>
        <span class="section-title">文档列表</span>
        <el-button class="refresh-btn" text @click="refresh">刷新</el-button>
      </template>
      <el-table :data="documents" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="source" label="来源文件" min-width="160" show-overflow-tooltip />
        <el-table-column prop="chunkCount" label="切片数" width="90" align="center" />
        <el-table-column prop="createTime" label="导入时间" width="180" />
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="danger" text size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 检索测试 -->
    <el-card class="section-card" shadow="never">
      <template #header><span class="section-title">检索测试</span></template>
      <div class="search-bar">
        <el-input
          v-model="searchForm.q"
          placeholder="输入问题，例如：取消政策 / 押金 / 早餐"
          clearable
          style="flex: 1"
          @keyup.enter="doSearch"
        />
        <el-select v-model="searchForm.mode" style="width: 130px">
          <el-option label="hybrid" value="hybrid" />
          <el-option label="vector" value="vector" />
          <el-option label="keyword" value="keyword" />
        </el-select>
        <el-input-number v-model="searchForm.topK" :min="1" :max="10" style="width: 110px" />
        <el-button type="primary" :loading="searching" @click="doSearch">
          <el-icon><Search /></el-icon> 检索
        </el-button>
      </div>

      <el-table :data="searchResults" v-loading="searching" size="small" class="result-table">
        <el-table-column prop="docTitle" label="来源文档" width="180" show-overflow-tooltip />
        <el-table-column prop="seq" label="片段" width="70" align="center" />
        <el-table-column label="相关度" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="scoreType(row.score)">{{ (row.score * 100).toFixed(0) }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="snippet" label="命中片段" min-width="320" show-overflow-tooltip />
      </el-table>
      <div v-if="!searching && searched && searchResults.length === 0" class="empty-tip">
        没有命中任何片段，可以调低阈值或换一种检索模式
      </div>
    </el-card>

    <!-- 评测结果 -->
    <el-dialog v-model="evalVisible" title="检索评测（Recall@3）" width="720px">
      <div v-if="evalResult">
        <el-descriptions :column="3" border class="eval-summary">
          <el-descriptions-item label="keyword">{{ evalResult.summary.keyword }}</el-descriptions-item>
          <el-descriptions-item label="vector">{{ evalResult.summary.vector }}</el-descriptions-item>
          <el-descriptions-item label="hybrid">{{ evalResult.summary.hybrid }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="evalResult.cases" size="small" max-height="360">
          <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
          <el-table-column label="keyword" width="90" align="center">
            <template #default="{ row }">{{ hitText(row.modes.keyword) }}</template>
          </el-table-column>
          <el-table-column label="vector" width="90" align="center">
            <template #default="{ row }">{{ hitText(row.modes.vector) }}</template>
          </el-table-column>
          <el-table-column label="hybrid" width="90" align="center">
            <template #default="{ row }">{{ hitText(row.modes.hybrid) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Search, Refresh, DataAnalysis } from '@element-plus/icons-vue'
import {
  getKbStats,
  getKbDocuments,
  searchKb,
  rebuildKb,
  evalKb,
  uploadKbDocument,
  deleteKbDocument
} from '@/api/kb'

const stats = ref({})
const documents = ref([])
const loading = ref(false)
const rebuilding = ref(false)
const uploading = ref(false)
const evaluating = ref(false)

const searchForm = ref({ q: '', mode: 'hybrid', topK: 3 })
const searchResults = ref([])
const searching = ref(false)
const searched = ref(false)

const evalVisible = ref(false)
const evalResult = ref(null)

async function refresh() {
  loading.value = true
  try {
    const [statsRes, docsRes] = await Promise.all([getKbStats(), getKbDocuments()])
    stats.value = statsRes.data || {}
    documents.value = docsRes.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载知识库失败')
  } finally {
    loading.value = false
  }
}

async function handleUpload(options) {
  const formData = new FormData()
  formData.append('file', options.file)
  uploading.value = true
  try {
    const res = await uploadKbDocument(formData)
    const data = res.data || {}
    ElMessage.success(`上传成功：《${data.title || data.source || '文档'}》，${data.chunks || 0} 个切片`)
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除《${row.title}》及其 ${row.chunkCount} 个切片吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await deleteKbDocument(row.id)
    ElMessage.success('已删除')
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function handleRebuild() {
  try {
    await ElMessageBox.confirm('将重新切块并向量化全部文档，耗时取决于文档数量，继续吗？', '重建索引', {
      type: 'warning',
      confirmButtonText: '开始',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  rebuilding.value = true
  try {
    const res = await rebuildKb()
    const data = res.data || {}
    ElMessage.success(`重建完成：${data.reindexed ?? 0} 篇重新切片，新增 ${data.imported ?? 0} 篇，共 ${data.chunks ?? 0} 个切片`)
    await refresh()
  } catch (e) {
    ElMessage.error(e.message || '重建失败')
  } finally {
    rebuilding.value = false
  }
}

async function handleEval() {
  evaluating.value = true
  try {
    const res = await evalKb()
    evalResult.value = res.data
    evalVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '评测失败')
  } finally {
    evaluating.value = false
  }
}

async function doSearch() {
  const q = (searchForm.value.q || '').trim()
  if (!q) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  searched.value = true
  try {
    const res = await searchKb({ q, topK: searchForm.value.topK, mode: searchForm.value.mode })
    searchResults.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message || '检索失败')
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

function scoreType(score) {
  const value = Number(score) || 0
  if (value >= 0.7) return 'success'
  if (value >= 0.4) return 'warning'
  return 'info'
}

function hitText(mode) {
  if (!mode || mode.available === false) return 'N/A'
  return mode.hit ? '✅' : '❌'
}

onMounted(refresh)
</script>

<style scoped>
.knowledge-page {
  padding: 20px 24px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px 16px;
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #409eff;
  line-height: 1.3;
}

.stat-value.small {
  font-size: 15px;
}

.stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.section-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.section-title {
  font-weight: 600;
  color: #303133;
}

.refresh-btn {
  float: right;
}

.upload-icon {
  font-size: 38px;
  color: #c0c4cc;
  margin-bottom: 8px;
}

.search-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
  align-items: center;
}

.result-table {
  margin-top: 4px;
}

.empty-tip {
  padding: 18px 0;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.eval-summary {
  margin-bottom: 12px;
}
</style>
