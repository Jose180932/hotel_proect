import request from '@/utils/request'

/**
 * 知识库统计：文档数 / 切片数 / 向量化数 / 检索模式
 */
export function getKbStats() {
  return request({
    url: '/api/admin/kb/stats',
    method: 'get'
  })
}

/**
 * 文档列表
 */
export function getKbDocuments() {
  return request({
    url: '/api/admin/kb/documents',
    method: 'get'
  })
}

/**
 * 检索测试：q / topK / mode(keyword|vector|hybrid)
 */
export function searchKb(params) {
  return request({
    url: '/api/admin/kb/search',
    method: 'get',
    params
  })
}

/**
 * 重建全部索引（重新切块 + 重新向量化）
 */
export function rebuildKb() {
  return request({
    url: '/api/admin/kb/rebuild',
    method: 'post'
  })
}

/**
 * 固定问题集评测（Recall@3 对比）
 */
export function evalKb() {
  return request({
    url: '/api/admin/kb/eval',
    method: 'post'
  })
}

/**
 * 上传文档（.md / .txt / .docx）
 */
export function uploadKbDocument(formData) {
  return request({
    url: '/api/admin/kb/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

/**
 * 删除文档及其切片
 */
export function deleteKbDocument(id) {
  return request({
    url: `/api/admin/kb/documents/${id}`,
    method: 'delete'
  })
}

export default {
  getKbStats,
  getKbDocuments,
  searchKb,
  rebuildKb,
  evalKb,
  uploadKbDocument,
  deleteKbDocument
}
