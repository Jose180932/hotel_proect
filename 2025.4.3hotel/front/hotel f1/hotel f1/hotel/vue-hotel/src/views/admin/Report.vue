<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getCustomerStats, getIncomeStats, getOccupancyStats, getPerformanceList } from '@/api/admin'

const reports = {
  revenue: {
    label: '营收报表',
    columns: [
      ['date', '月份'], ['roomFee', '房费（元）'], ['depositFee', '押金（元）'],
      ['otherFee', '其他收入（元）'], ['totalAmount', '总收入（元）']
    ],
    chart: ['totalAmount'],
    fetch: async range => (await getIncomeStats({ ...range, groupBy: 'month' })).data
  },
  occupancy: {
    label: '入住率报表',
    columns: [
      ['date', '日期'], ['totalRooms', '总房间数'],
      ['occupiedRooms', '已入住房间数'], ['occupancyRate', '入住率（%）']
    ],
    chart: ['occupancyRate'],
    fetch: async range => (await getOccupancyStats({ ...range, groupBy: 'day' })).data
  },
  staff: {
    label: '员工绩效报表',
    columns: [
      ['month', '月份'], ['empNo', '工号'], ['employeeName', '姓名'],
      ['score', '绩效评分'], ['evaluation', '评语']
    ],
    fetch: async ({ startDate, endDate }) => {
      const params = { startMonth: startDate.slice(0, 7), endMonth: endDate.slice(0, 7), pageSize: 100 }
      const rows = []
      let pageNum = 1
      while (true) {
        const { data } = await getPerformanceList({ ...params, pageNum })
        rows.push(...data.records)
        if (rows.length >= data.total || data.records.length === 0) return rows
        pageNum += 1
      }
    }
  },
  customer: {
    label: '客户分析报表',
    columns: [
      ['month', '月份'], ['newCustomers', '当月首次入住客户'],
      ['returningCustomers', '再次入住客户'], ['averageStay', '平均入住天数'],
      ['averageOrderAmount', '平均订单金额（元）']
    ],
    chart: ['newCustomers', 'returningCustomers'],
    fetch: async range => (await getCustomerStats(range)).data
  }
}

const ranges = [
  ['last7days', '最近 7 天'], ['last30days', '最近 30 天'], ['thisMonth', '本月'],
  ['lastMonth', '上月'], ['thisYear', '今年'], ['custom', '自定义']
]
const reportType = ref('revenue')
const timeRange = ref('thisMonth')
const customDates = ref([])
const generated = ref(null)
const loading = ref(false)
const error = ref('')
const chartElement = ref(null)
let chart

const definition = computed(() => generated.value && reports[generated.value.type])
const rows = computed(() => generated.value?.rows ?? [])

function localDate(date) {
  return [date.getFullYear(), date.getMonth() + 1, date.getDate()]
    .map(part => String(part).padStart(2, '0')).join('-')
}

function selectedDates() {
  if (timeRange.value === 'custom') return customDates.value ?? []
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth()
  const day = now.getDate()
  switch (timeRange.value) {
    case 'last7days': return [localDate(new Date(year, month, day - 6)), localDate(now)]
    case 'last30days': return [localDate(new Date(year, month, day - 29)), localDate(now)]
    case 'lastMonth': return [localDate(new Date(year, month - 1, 1)), localDate(new Date(year, month, 0))]
    case 'thisYear': return [localDate(new Date(year, 0, 1)), localDate(now)]
    default: return [localDate(new Date(year, month, 1)), localDate(now)]
  }
}

function renderChart() {
  chart?.dispose()
  chart = undefined
  if (!chartElement.value || !rows.value.length || !definition.value?.chart) return
  const labels = Object.fromEntries(definition.value.columns)
  const [dateKey] = definition.value.columns[0]
  chart = echarts.init(chartElement.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: {},
    grid: { left: 58, right: 24, top: 44, bottom: 40 },
    xAxis: { type: 'category', data: rows.value.map(row => row[dateKey]) },
    yAxis: { type: 'value' },
    series: definition.value.chart.map(key => ({
      name: labels[key],
      type: 'line',
      data: rows.value.map(row => row[key])
    }))
  })
}

async function generateReport() {
  if (loading.value) return
  const [startDate, endDate] = selectedDates()
  if (!startDate || !endDate || startDate > endDate) {
    ElMessage.warning('请选择有效的起止日期')
    return
  }

  const type = reportType.value
  loading.value = true
  error.value = ''
  try {
    const data = await reports[type].fetch({ startDate, endDate })
    generated.value = { type, startDate, endDate, rows: data }
    await nextTick()
    renderChart()
  } catch (cause) {
    error.value = '报表生成失败，请重试。'
    console.error('生成报表失败', cause)
  } finally {
    loading.value = false
  }
}

function csvCell(value) {
  let text = String(value ?? '')
  if (typeof value === 'string' && /^[\s=+@-]/.test(text)) text = "'" + text
  return '"' + text.replace(/"/g, '""') + '"'
}

function exportReport() {
  const { columns, label } = definition.value
  const csv = [
    columns.map(([, heading]) => csvCell(heading)).join(','),
    ...rows.value.map(row => columns.map(([key]) => csvCell(row[key])).join(','))
  ].join('\r\n')
  const url = URL.createObjectURL(new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = label + '-' + generated.value.startDate + '-' + generated.value.endDate + '.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function resizeChart() {
  chart?.resize()
}

function printReport() {
  window.print()
}

onMounted(() => {
  window.addEventListener('resize', resizeChart)
  generateReport()
})
onUnmounted(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>

<template>
  <div class="container mx-auto p-4">
    <h1 class="text-2xl font-bold mb-4">报表统计</h1>
    <el-card class="no-print mb-4">
      <el-form inline>
        <el-form-item label="报表类型">
          <el-select v-model="reportType" style="width: 170px">
            <el-option v-for="(item, key) in reports" :key="key" :label="item.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="timeRange" style="width: 140px">
            <el-option v-for="[key, label] in ranges" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="timeRange === 'custom'" label="日期范围">
          <el-date-picker v-model="customDates" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始日期" end-placeholder="结束日期" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="generateReport">生成报表</el-button>
          <el-button :disabled="loading || !rows.length" @click="exportReport">导出 CSV</el-button>
          <el-button :disabled="loading || !rows.length" @click="printReport">打印</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="error" :title="error" type="error" :closable="false" class="no-print mb-4" />
    <el-card v-if="generated" v-loading="loading">
      <template #header>
        <h2 class="text-lg font-semibold">{{ definition.label }}</h2>
        <p class="text-sm text-gray-500">{{ generated.startDate }} 至 {{ generated.endDate }}</p>
      </template>
      <div v-if="rows.length && definition.chart" ref="chartElement" class="report-chart" />
      <el-table :data="rows" empty-text="所选日期范围内暂无数据" stripe>
        <el-table-column v-for="[key, label] in definition.columns" :key="key"
                         :prop="key" :label="label" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.report-chart {
  height: 280px;
}

@media print {
  .no-print {
    display: none;
  }
}
</style>
