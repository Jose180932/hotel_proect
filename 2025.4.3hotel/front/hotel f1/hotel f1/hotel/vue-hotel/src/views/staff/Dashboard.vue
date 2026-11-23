<template>
  <div class="dashboard-container" v-loading="loading">
    <div class="dashboard-header">
      <h2>员工工作台</h2>
      <div class="header-actions">
        <el-button type="primary" @click="refreshAllData">
          <el-icon><Refresh /></el-icon>
          刷新数据
        </el-button>
      </div>
    </div>

    <!-- 签到卡片 -->
    <el-card class="attendance-card">
      <div class="attendance-status">
        <div class="status-info">
          <h3>今日考勤</h3>
          <div class="attendance-times">
            <p v-if="attendanceStatus.hasCheckedIn">
              <el-icon><Check /></el-icon>
              已签到: {{ attendanceStatus.checkInTime }}
            </p>
            <p v-else>未签到</p>
            <p v-if="attendanceStatus.hasCheckedOut">
              <el-icon><Check /></el-icon>
              已签退: {{ attendanceStatus.checkOutTime }}
            </p>
            <p v-else-if="attendanceStatus.hasCheckedIn">未签退</p>
          </div>
        </div>
        <div class="action-buttons">
          <el-button 
            type="primary" 
            @click="handleCheckIn" 
            :disabled="attendanceStatus.hasCheckedIn"
          >签到</el-button>
          <el-button 
            type="warning" 
            @click="handleCheckOut" 
            :disabled="!attendanceStatus.hasCheckedIn || attendanceStatus.hasCheckedOut"
          >签退</el-button>
        </div>
      </div>
    </el-card>

    <!-- 数据概览 -->
    <div class="stats-row">
      <el-row :gutter="20">
        <el-col :span="6">
          <el-card shadow="hover" class="stats-card">
            <div class="stats-content">
              <el-icon class="stats-icon" color="#409EFF"><House /></el-icon>
              <div class="stats-info">
                <div class="stats-value">{{ roomStats.available }}</div>
                <div class="stats-label">可用房间</div>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="stats-card">
            <div class="stats-content">
              <el-icon class="stats-icon" color="#67C23A"><Check /></el-icon>
              <div class="stats-info">
                <div class="stats-value">{{ roomStats.occupied }}</div>
                <div class="stats-label">已入住</div>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="stats-card">
            <div class="stats-content">
              <el-icon class="stats-icon" color="#E6A23C"><Bell /></el-icon>
              <div class="stats-info">
                <div class="stats-value">{{ roomStats.maintenance }}</div>
                <div class="stats-label">维修中</div>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="stats-card">
            <div class="stats-content">
              <el-icon class="stats-icon" color="#F56C6C"><Menu /></el-icon>
              <div class="stats-info">
                <div class="stats-value">{{ roomStats.cleaning }}</div>
                <div class="stats-label">清扫中</div>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- 待处理事项 -->
    <div class="tasks-row">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-card class="task-card">
            <template #header>
              <div class="card-header">
                <h3>待入住订单</h3>
                <el-button text @click="goToOrderManagement">查看全部</el-button>
              </div>
            </template>
            <el-table :data="pendingOrders" stripe style="width: 100%">
              <el-table-column prop="orderNo" label="订单号" width="120" />
              <el-table-column prop="guestName" label="客人姓名" width="100" />
              <el-table-column prop="roomTypeName" label="房型" width="120" />
              <el-table-column prop="checkInDate" label="入住日期" width="100" />
              <el-table-column fixed="right" label="操作" width="120">
                <template #default="scope">
                  <el-button 
                    type="primary" 
                    size="small" 
                    @click="handleViewOrder(scope.row.id)"
                  >处理</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="task-card">
            <template #header>
              <div class="card-header">
                <h3>最近客人记录</h3>
                <el-button text @click="goToGuestManagement">查看全部</el-button>
              </div>
            </template>
            <el-table :data="recentGuests" stripe style="width: 100%">
              <el-table-column prop="guestName" label="客人姓名" width="100" />
              <el-table-column prop="roomNumber" label="房间号" width="100" />
              <el-table-column prop="checkInTime" label="入住时间" width="160" />
              <el-table-column prop="checkOutDate" label="预计退房日期" width="120" />
              <el-table-column fixed="right" label="操作" width="120">
                <template #default="scope">
                  <el-button 
                    type="primary" 
                    size="small" 
                    @click="handleViewCheckIn(scope.row.id)"
                  >查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- 快捷操作 -->
    <div class="quick-actions">
      <h3>快捷操作</h3>
      <el-row :gutter="20">
        <el-col :span="6">
          <el-card shadow="hover" class="action-card" @click="goToOrderManagement">
            <el-icon><Tickets /></el-icon>
            <span>预订管理</span>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="action-card" @click="goToOrderManagement">
            <el-icon><Right /></el-icon>
            <span>入住办理</span>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="action-card" @click="goToCheckOutManagement">
            <el-icon><Remove /></el-icon>
            <span>退房办理</span>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover" class="action-card" @click="goToRoomManagement">
            <el-icon><House /></el-icon>
            <span>房态管理</span>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { 
  Calendar, 
  Check, 
  Bell, 
  House,
  Tickets,
  Right,
  Remove,
  Menu,
  Refresh
} from '@element-plus/icons-vue'
import { checkIn, checkOut, getAttendanceStatus } from '@/api/employee'
import { getRoomStatusList, getOrderList, getCheckInList } from '@/api/reception'

const router = useRouter()
const loading = ref(false)

// 签到状态
const attendanceStatus = ref({
  hasCheckedIn: false,
  hasCheckedOut: false,
  checkInTime: '',
  checkOutTime: ''
})

// 房态统计
const roomStats = ref({
  available: 0,
  occupied: 0,
  maintenance: 0,
  cleaning: 0
})

// 待入住订单
const pendingOrders = ref([])

// 最近客人记录
const recentGuests = ref([])

// 获取签到状态
const fetchAttendanceStatus = async () => {
  try {
    const res = await getAttendanceStatus()
    if (res.code === 200) {
      const { checkInTime, checkOutTime } = res.data
      attendanceStatus.value = {
        hasCheckedIn: Boolean(checkInTime),
        hasCheckedOut: Boolean(checkOutTime),
        checkInTime,
        checkOutTime
      }
    }
  } catch (error) {
    console.error('获取签到状态失败:', error)
    ElMessage.error('获取签到状态失败')
  }
}

// 处理签到
const handleCheckIn = async () => {
  try {
    const res = await checkIn()
    if (res.code === 200) {
      ElMessage.success('签到成功')
      fetchAttendanceStatus()
    }
  } catch (error) {
    console.error('签到失败:', error)
    ElMessage.error('签到失败')
  }
}

// 处理签退
const handleCheckOut = async () => {
  try {
    const res = await checkOut()
    if (res.code === 200) {
      ElMessage.success('签退成功')
      fetchAttendanceStatus()
    }
  } catch (error) {
    console.error('签退失败:', error)
    ElMessage.error('签退失败')
  }
}

// 获取房态统计
const fetchRoomStats = async () => {
  try {
    const statuses = { available: 1, occupied: 2, cleaning: 3, maintenance: 4 }
    const totals = await Promise.all(Object.entries(statuses).map(async ([key, status]) => {
      const { data } = await getRoomStatusList({ status, pageNum: 1, pageSize: 1 })
      return [key, data.total]
    }))
    roomStats.value = Object.fromEntries(totals)
  } catch (error) {
    console.error('获取房态统计失败:', error)
    ElMessage.error('获取房态统计失败')
  }
}

// 获取已支付待入住的订单
const fetchPendingOrders = async () => {
  try {
    const res = await getOrderList({
      status: 2,
      pageNum: 1,
      pageSize: 5
    })
    if (res.code === 200) {
      pendingOrders.value = res.data?.records ?? res.data?.list ?? []
    }
  } catch (error) {
    console.error('获取待入住订单失败:', error)
    ElMessage.error('获取待入住订单失败')
  }
}

// 获取最近客人记录
const fetchRecentGuests = async () => {
  try {
    const res = await getCheckInList({
      pageNum: 1,
      pageSize: 5
    })
    if (res.code === 200) {
      recentGuests.value = res.data?.records ?? res.data?.list ?? []
    }
  } catch (error) {
    console.error('获取最近客人记录失败:', error)
    ElMessage.error('获取最近客人记录失败')
  }
}

// 刷新所有数据
const refreshAllData = () => {
  loading.value = true
  Promise.all([
    fetchAttendanceStatus(),
    fetchRoomStats(),
    fetchPendingOrders(),
    fetchRecentGuests()
  ]).finally(() => {
    loading.value = false
    ElMessage.success('数据已更新')
  })
}

// 页面导航
const goToRoomManagement = () => router.push({ name: 'StaffRoomStatus' })
const goToOrderManagement = () => router.push({ name: 'StaffOrders' })
const goToCheckOutManagement = () => router.push({ name: 'StaffCheckOut' })
const goToGuestManagement = () => router.push({ name: 'StaffGuest' })

// 查看详情
const handleViewOrder = (orderId) => router.push({ name: 'StaffOrder', query: { orderId } })
const handleViewCheckIn = (checkInId) => router.push({ name: 'StaffCheckInList', query: { checkInId } })

onMounted(() => {
  refreshAllData()
})
</script>

<style scoped>
.dashboard-container {
  padding: 20px;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.dashboard-header h2 {
  margin: 0;
  font-size: 24px;
  color: #303133;
}

.attendance-card {
  margin-bottom: 20px;
}

.attendance-status {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.status-info h3 {
  margin: 0 0 10px 0;
  color: #303133;
}

.attendance-times {
  color: #606266;
}

.attendance-times p {
  margin: 5px 0;
  display: flex;
  align-items: center;
  gap: 5px;
}

.stats-row {
  margin-bottom: 20px;
}

.stats-card {
  height: 100%;
}

.stats-content {
  display: flex;
  align-items: center;
  padding: 10px;
}

.stats-icon {
  font-size: 48px;
  margin-right: 15px;
}

.stats-info {
  flex: 1;
}

.stats-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 5px;
}

.stats-label {
  font-size: 14px;
  color: #909399;
}

.tasks-row {
  margin-bottom: 20px;
}

.task-card {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.quick-actions {
  margin-top: 20px;
}

.quick-actions h3 {
  margin-bottom: 15px;
  font-size: 16px;
  color: #303133;
}

.action-card {
  text-align: center;
  cursor: pointer;
  padding: 20px;
  transition: all 0.3s;
}

.action-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1);
}

.action-card .el-icon {
  font-size: 32px;
  color: #409EFF;
  margin-bottom: 10px;
}

.action-card span {
  display: block;
  color: #606266;
}
</style> 