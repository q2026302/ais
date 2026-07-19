<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { authApi, type UserProfile } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const changingPassword = ref(false)
const profile = reactive({ displayName: '', email: '' })
const account = ref<UserProfile | null>(null)
const password = reactive({ current: '', next: '', confirm: '' })

async function load() {
  loading.value = true
  try {
    const result = await authApi.me()
    if (!result.authenticated) {
      await router.replace({ name: 'login' })
      return
    }
    account.value = {
      id: result.id ?? null,
      username: result.username || result.subject || '',
      subject: result.subject,
      displayName: result.displayName ?? null,
      email: result.email ?? null,
      role: result.role || 'USER',
      enabled: result.enabled ?? true,
      createdAt: result.createdAt ?? null,
      updatedAt: result.updatedAt ?? null,
    }
    profile.displayName = account.value.displayName || ''
    profile.email = account.value.email || ''
  } catch (error: any) {
    ElMessage.error(error.message || '加载个人信息失败')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  saving.value = true
  try {
    const result = await authApi.updateProfile(profile)
    if (account.value) {
      account.value.displayName = result.displayName
      account.value.email = result.email
    }
    ElMessage.success('个人信息已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存个人信息失败')
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  if (!password.current || !password.next) {
    ElMessage.warning('请输入当前密码和新密码')
    return
  }
  if (password.next.length < 6) {
    ElMessage.warning('新密码至少需要 6 个字符')
    return
  }
  if (password.next !== password.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await authApi.changePassword(password.current, password.next)
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await auth.logout()
    await router.replace({ name: 'login' })
  } catch (error: any) {
    ElMessage.error(error.message || '修改密码失败')
  } finally {
    password.current = ''
    password.next = ''
    password.confirm = ''
    changingPassword.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="profile-view" v-loading="loading">
    <div class="profile-header">
      <div class="profile-identity">
        <div class="profile-avatar" aria-hidden="true">
          {{ (account?.displayName || account?.username || 'AI').slice(0, 1).toUpperCase() }}
        </div>
        <div>
          <div class="eyebrow">ACCOUNT CENTER</div>
          <h1>个人中心</h1>
          <p>维护个人资料并管理登录密码。</p>
          <div v-if="account" class="account-meta">
            <span>@{{ account.username }}</span>
            <span class="meta-separator">·</span>
            <span class="status-dot">账号已启用</span>
          </div>
        </div>
      </div>
      <el-tag v-if="account" :type="account.role === 'ADMIN' ? 'warning' : 'primary'" effect="light">
        {{ account.role === 'ADMIN' ? '管理员' : '普通用户' }}
      </el-tag>
    </div>

    <div class="profile-grid">
      <el-card class="profile-card" shadow="never">
        <template #header>
          <div class="card-title"><User /> <strong>基本信息</strong></div>
        </template>
        <el-form label-position="top" @submit.prevent="saveProfile">
          <el-form-item label="用户名">
            <el-input :model-value="account?.username" disabled />
            <small class="hint">用户名用于登录，创建后不能修改。</small>
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="profile.displayName" maxlength="100" show-word-limit placeholder="例如：张三" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="profile.email" maxlength="254" placeholder="用于联系的邮箱（可选）" />
          </el-form-item>
          <el-button type="primary" :loading="saving" @click="saveProfile">保存个人信息</el-button>
        </el-form>
      </el-card>

      <el-card class="profile-card" shadow="never">
        <template #header>
          <div class="card-title"><Lock /> <strong>修改密码</strong></div>
        </template>
        <el-form label-position="top" @submit.prevent="changePassword">
          <el-form-item label="当前密码">
            <el-input v-model="password.current" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="password.next" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="password.confirm" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <p class="hint">密码仅在浏览器端计算摘要后通过 RSA 加密传输。</p>
          <el-button type="primary" :loading="changingPassword" @click="changePassword">修改密码</el-button>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.profile-view {
  position: relative;
  width: min(100%, 1120px);
  min-height: 100%;
  margin: 0 auto;
  padding: clamp(22px, 4vw, 42px) clamp(14px, 3vw, 32px) 56px;
}

.profile-view::before {
  position: absolute;
  z-index: -1;
  top: 0;
  right: 5%;
  width: 280px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(136, 113, 255, .16), transparent 70%);
  content: '';
  pointer-events: none;
}

.profile-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: clamp(20px, 3vw, 28px);
  margin-bottom: 22px;
  border: 1px solid rgba(218, 222, 247, .9);
  border-radius: 22px;
  background: linear-gradient(120deg, rgba(255, 255, 255, .96), rgba(246, 247, 255, .92));
  box-shadow: 0 12px 30px rgba(56, 69, 139, .08);
}

.profile-identity { display: flex; align-items: center; gap: 16px; min-width: 0; }
.profile-avatar {
  display: grid;
  flex: 0 0 auto;
  width: 58px;
  height: 58px;
  place-items: center;
  color: #fff;
  font-size: 24px;
  font-weight: 800;
  border: 4px solid rgba(255, 255, 255, .85);
  border-radius: 18px;
  background: linear-gradient(135deg, #586bf7, #9b5cf6);
  box-shadow: 0 9px 18px rgba(82, 93, 220, .25);
}

.eyebrow { color: #7f8ab0; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
h1 { margin: 5px 0 4px; color: #273453; font-size: clamp(24px, 3vw, 30px); letter-spacing: -.03em; }
.profile-header p { margin: 0; color: #8791aa; }
.account-meta { display: flex; align-items: center; gap: 7px; margin-top: 9px; color: #7a86a5; font-size: 12px; }
.meta-separator { color: #bbc1d4; }
.status-dot::before { display: inline-block; width: 6px; height: 6px; margin: 0 5px 1px 0; border-radius: 50%; background: #2fc58b; content: ''; }
.profile-header :deep(.el-tag) { flex: 0 0 auto; margin-top: 2px; border: 0; border-radius: 999px; font-weight: 700; }
.profile-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; align-items: stretch; }
.profile-card { height: 100%; border: 1px solid rgba(224, 227, 243, .95); border-radius: 18px; background: rgba(255, 255, 255, .95); box-shadow: 0 10px 28px rgba(56, 69, 139, .06); }
.profile-card :deep(.el-card__header) { padding: 18px 22px 15px; border-bottom-color: #edf0fa; }
.profile-card :deep(.el-card__body) { padding: 20px 22px 22px; }
.card-title { display: flex; align-items: center; gap: 9px; color: #3d4966; font-size: 15px; }
.card-title svg { width: 17px; color: #5267f6; }
.profile-card :deep(.el-form-item) { margin-bottom: 17px; }
.profile-card :deep(.el-form-item__label) { padding-bottom: 6px; color: #66718d; font-size: 13px; font-weight: 700; }
.profile-card :deep(.el-button) { min-height: 38px; margin-top: 2px; border-radius: 10px; }
.hint { display: block; margin-top: 4px; color: #929bae; font-size: 12px; line-height: 1.5; }

@media (max-width: 760px) {
  .profile-grid { grid-template-columns: 1fr; }
  .profile-header { flex-direction: column; }
  .profile-header :deep(.el-tag) { margin-top: 0; }
}

@media (max-width: 460px) {
  .profile-identity { align-items: flex-start; }
  .profile-avatar { width: 48px; height: 48px; font-size: 20px; border-radius: 15px; }
  .profile-card :deep(.el-card__body), .profile-card :deep(.el-card__header) { padding-right: 16px; padding-left: 16px; }
}
</style>
