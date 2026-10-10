/**
 * 登录页逻辑：密码登录 / 短信登录 / 注册（注册即登录）/ 忘记密码。
 *
 * 启动时已登录则跳 index.html；成功后缓存 token + user 再跳转。
 */
const { createApp, ref, computed, onMounted } = Vue;

createApp({
    setup() {
        const mode = ref('pwd');            // pwd | sms(经 loginType) | register | reset
        const loginType = ref('pwd');       // 密码登录 | 短信登录
        const username = ref('');
        const password = ref('');
        const showPassword = ref(false);
        const phone = ref('');
        const smsCode = ref('');
        const realName = ref('');
        const loading = ref(false);
        const errorMsg = ref('');
        const codeCooldown = ref(0);
        const sending = ref(false);
        let cooldownTimer = null;

        const modeTitle = computed(() => {
            if (mode.value === 'register') return '注册';
            if (mode.value === 'reset') return '重置密码';
            return '账号登录';
        });

        /** 切换模式并清空错误 */
        function switchMode(m) {
            mode.value = m;
            errorMsg.value = '';
        }

        onMounted(async () => {
            const user = await DA.fetchCurrentUser();
            if (user) {
                window.location.href = 'dashboard.html';
            }
        });

        function startCooldown() {
            codeCooldown.value = 60;
            clearInterval(cooldownTimer);
            cooldownTimer = setInterval(() => {
                codeCooldown.value--;
                if (codeCooldown.value <= 0) clearInterval(cooldownTimer);
            }, 1000);
        }

        /** 发送验证码（登录/注册/重置共用）。发送中与冷却期均不可重复点击。 */
        async function sendCode() {
            if (sending.value || codeCooldown.value > 0) return;
            errorMsg.value = '';
            if (!/^1\d{10}$/.test(phone.value.trim())) {
                errorMsg.value = '请输入正确的手机号';
                return;
            }
            sending.value = true;
            try {
                // 注册模式：用户名 + 手机号重复预校验，避免用户白等一条短信
                if (mode.value === 'register') {
                    if (!username.value.trim()) {
                        errorMsg.value = '请先填写用户名';
                        return;
                    }
                    const checkU = await DA.apiGet('/auth/sms/check-username', { username: username.value.trim() });
                    if (checkU.data === true) {
                        errorMsg.value = '该用户名已被使用，请更换';
                        return;
                    }
                    const check = await DA.apiGet('/auth/sms/check-phone', { phone: phone.value.trim() });
                    if (check.data === true) {
                        errorMsg.value = '该手机号已注册，请直接登录';
                        return;
                    }
                }
                await DA.apiPost('/auth/sms/send-code', { phone: phone.value.trim() });
                DA.showToast('验证码已发送，请查收短信');
                startCooldown();
            } catch (e) {
                errorMsg.value = e.message || '验证码发送失败';
            } finally {
                sending.value = false;
            }
        }

        function handleAuthSuccess(resp, msg) {
            // 业务失败时后端返回 data=null：先判空，展示后端 msg（如"密码错误"），避免原生 JS 报错
            if (!resp || !resp.data || !resp.data.token) {
                errorMsg.value = (resp && resp.msg) || '登录失败，请检查账号或密码';
                return;
            }
            DA.setToken(resp.data.token);
            DA.cacheUser(resp.data);
            DA.showToast(msg || '登录成功');
            setTimeout(() => { window.location.href = 'dashboard.html'; }, 300);
        }

        /** 登录（密码 / 短信） */
        async function onSubmit() {
            errorMsg.value = '';
            loading.value = true;
            try {
                let resp;
                if (loginType.value === 'pwd') {
                    if (!username.value.trim() || !password.value) {
                        errorMsg.value = '请输入用户名和密码';
                        return;
                    }
                    resp = await DA.apiPost('/auth/login', {
                        username: username.value.trim(), password: password.value
                    });
                } else {
                    if (!phone.value.trim() || !smsCode.value.trim()) {
                        errorMsg.value = '请输入手机号和验证码';
                        return;
                    }
                    resp = await DA.apiPost('/auth/sms/login', {
                        phone: phone.value.trim(), code: smsCode.value.trim()
                    });
                }
                handleAuthSuccess(resp);
            } catch (e) {
                errorMsg.value = e.message || '登录失败，请稍后重试';
            } finally {
                loading.value = false;
            }
        }

        /** 注册（注册即登录） */
        async function onRegister() {
            errorMsg.value = '';
            if (!username.value.trim() || !phone.value.trim() || !smsCode.value.trim() || !password.value) {
                errorMsg.value = '请完整填写用户名、手机号、验证码和密码';
                return;
            }
            loading.value = true;
            try {
                const resp = await DA.apiPost('/auth/register', {
                    username: username.value.trim(),
                    phone: phone.value.trim(),
                    code: smsCode.value.trim(),
                    password: password.value,
                    realName: realName.value.trim() || null
                });
                handleAuthSuccess(resp, '注册成功');
            } catch (e) {
                errorMsg.value = e.message || '注册失败';
            } finally {
                loading.value = false;
            }
        }

        /** 忘记密码重置 */
        async function onReset() {
            errorMsg.value = '';
            if (!phone.value.trim() || !smsCode.value.trim() || !password.value) {
                errorMsg.value = '请完整填写手机号、验证码和新密码';
                return;
            }
            loading.value = true;
            try {
                await DA.apiPost('/auth/reset-password', {
                    phone: phone.value.trim(),
                    code: smsCode.value.trim(),
                    newPassword: password.value
                });
                DA.showToast('密码已重置，请使用新密码登录');
                mode.value = 'pwd';
                loginType.value = 'pwd';
                smsCode.value = '';
                password.value = '';
            } catch (e) {
                errorMsg.value = e.message || '重置失败';
            } finally {
                loading.value = false;
            }
        }

        return {
            mode, modeTitle, switchMode, loginType, username, password, showPassword,
            phone, smsCode, realName, loading, errorMsg, codeCooldown, sending,
            sendCode, onSubmit, onRegister, onReset
        };
    }
}).mount('#da-login-app');
