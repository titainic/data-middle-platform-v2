<!--
  Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.

  This file is part of qData Data Middle Platform (Open Source Edition).

  qData is licensed under Apache License 2.0 with additional qData terms.
  You may use qData for commercial purposes, but you may not remove, hide,
  modify, or replace the qData logo, copyright notices, license notices,
  or attribution information without separate commercial license.

  White-label use, OEM distribution, rebranding, or presenting qData as
  another product requires separate commercial authorization from
  Jiangsu Qiantong Technology Co., Ltd.

  Business License: https://community.qdata.tech/business/policy.html
  See the LICENSE file in the project root for full license information.
-->

<template>
    <!-- Minimal login page: greeting + login card only -->
    <div class="app-container login-minimal sysInfo sysInfo-wrap" ref="app-container">
        <div class="login-wrap">
            <div class="greeting">
                <div class="entry_period">{{ td('login.greeting.greetingHello') }}，{{ greeting }}！</div>
                <div class="entry_greeting">{{ td('login.greeting.greetingMessage') }}</div>
            </div>
            <div class="login-panel">
                <el-form ref="loginRef" :model="loginForm" :rules="loginRules">
                    <p class="titles">{{ td('login.title') }}</p>
                    <div class="titles-bar"></div>
                    <el-form-item prop="username">
                        <el-input v-model="loginForm.username" type="text" auto-complete="off" :placeholder="td('login.username')">
                            <template #prefix>
                                <i class="iconfont">&#xebc0;</i>
                            </template>
                        </el-input>
                    </el-form-item>
                    <el-form-item prop="password">
                        <el-input v-model="loginForm.password" type="password" auto-complete="off" :placeholder="td('login.password')"
                            @keyup.enter="handleLogin">
                            <template #prefix>
                                <i class="iconfont">&#xeb8d;</i>
                            </template>
                        </el-input>
                    </el-form-item>
                    <el-form-item v-if="captchaEnabled" prop="code">
                        <div class="login-captcha">
                            <el-input v-model="loginForm.code" :placeholder="td('login.codePlaceholder')"
                                auto-complete="off" @keyup.enter="handleLogin" />
                            <button type="button" class="captcha-refresh" :aria-label="td('login.getCode')" @click="getCode">
                                <img v-if="codeUrl" :src="codeUrl" :alt="td('login.code')" />
                                <span v-else>{{ td('login.getCode') }}</span>
                            </button>
                        </div>
                    </el-form-item>
                    <el-form-item style="width: 100%">
                        <el-button :loading="loading" type="primary" style="width: 100%"
                            :disabled="!captchaReady"
                            @click="handleLogin">
                            <span v-if="!loading">{{ td('login.loginBtn') }}</span>
                            <span v-else>{{ td('login.logining') }}</span>
                        </el-button>
                    </el-form-item>

                    <div class="form-actions">
                        <el-checkbox v-model="loginForm.rememberMe">{{ td('login.remember') }}</el-checkbox>
                        <el-text type="primary" @click="dialogVisible = true">{{ td('login.forgetPassword') }}</el-text>
                    </div>
                </el-form>
            </div>
        </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="td('login.forgetPassword')" class="fp-form-dialog" width="650px"
        :append-to="$refs['app-container']" draggable destroy-on-close>
        <el-form :model="fpForm" label-width="auto" style="padding: 10px 60px 0" :label-position="labelPosition">
            <el-row :gutter="20">
                <el-col :span="24">
                    <el-form-item :label="td('login.username')" :label-position="labelPosition">
                        <el-input v-model="fpForm.name" :placeholder="td('login.usernameInputPlaceholder')" />
                    </el-form-item>
                </el-col>
                <el-col :span="24">
                    <el-form-item :label="td('login.code')" :label-position="labelPosition">
                        <div class="wrapper">
                            <el-input v-model="fpForm.code" :placeholder="td('login.codePlaceholder')" />
                            <el-button type="primary" :disabled="codeFlag" style="margin-left: 10px"
                                @click="handleFPCodeClick">{{ codeFlag ? `${codeTime}s` : td('login.getCode') }}</el-button>
                        </div>
                    </el-form-item>
                </el-col>
                <el-col :span="24">
                    <el-form-item :label="td('login.newPassword')" :label-position="labelPosition">
                        <el-input v-model="fpForm.password" :placeholder="td('login.newPasswordPlaceholder')" />
                    </el-form-item>
                </el-col>
                <el-col :span="24">
                    <el-form-item :label="td('login.confirmPassword')" :label-position="labelPosition">
                        <el-input v-model="fpForm.password2" :placeholder="td('login.confirmPasswordPlaceholder')" />
                    </el-form-item>
                </el-col>
            </el-row>
        </el-form>
        <template #footer>
            <div class="dialog-footer">
                <el-button type="primary" @click="dialogVisible = false"> {{ td('login.resetPassword') }} </el-button>
            </div>
        </template>
    </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue';
import useDefaultLang from "@/composables/useDefaultLang";
import Cookies from 'js-cookie';
import { encrypt, decrypt } from '@/utils/jsencrypt';
import useUserStore from '@/store/system/user.js';
import { getCodeImg } from '@/api/system/login.js';
import { useTimeGreeting } from '@/composables/useTimeGreeting';

const { td } = useDefaultLang();
const { greeting } = useTimeGreeting();
const userStore = useUserStore();
const dialogVisible = ref(false);
const { proxy } = getCurrentInstance();
const loading = ref(false);
const captchaEnabled = ref(true);
const captchaReady = ref(false);
const codeUrl = ref('');

const codeFlag = ref(false);
const loginForm = ref({
    username: 'admin',
    password: '',
    rememberMe: false,
    code: '',
    uuid: ''
});
const fpForm = ref({
    username: '',
    password: '',
    password2: '',
    code: ''
});

const loginRules = computed(() => ({
    username: [{ required: true, trigger: 'blur', message: td('login.usernameRequired') }],
    password: [{ required: true, trigger: 'blur', message: td('login.passwordRequired') }],
    code: [{ required: captchaEnabled.value, trigger: 'blur', message: td('login.codeRequired') }]
}));

function getCookie() {
    const username = Cookies.get('username');
    const password = Cookies.get('password');
    const rememberMe = Cookies.get('rememberMe');
    loginForm.value = {
        ...loginForm.value,
        username: username === undefined ? loginForm.value.username : username,
        password: password === undefined ? loginForm.value.password : decrypt(password),
        rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
    };
}
getCookie();

async function getCode() {
    captchaReady.value = false;
    loginForm.value.code = '';
    try {
        const result = await getCodeImg();
        captchaEnabled.value = result.captchaEnabled !== false;
        codeUrl.value = captchaEnabled.value ? 'data:image/gif;base64,' + result.img : '';
        loginForm.value.uuid = result.uuid || '';
        captchaReady.value = true;
    } catch (_) {
        codeUrl.value = '';
    }
}
getCode();

function handleLogin() {
    if (!captchaReady.value || loading.value) return;
    localStorage.setItem('username', loginForm.value.username);
    proxy.$refs.loginRef.validate((valid) => {
        if (valid) {
            loading.value = true;
            // Check the Require to remember password setting and set it in cookies to remember username and password.
            if (loginForm.value.rememberMe) {
                Cookies.set('username', loginForm.value.username, { expires: 30 });
                Cookies.set('password', encrypt(loginForm.value.password), { expires: 30 });
                Cookies.set('rememberMe', loginForm.value.rememberMe, { expires: 30 });
            } else {
                // Otherwise remove
                Cookies.remove('username');
                Cookies.remove('password');
                Cookies.remove('rememberMe');
            }
            // Call the login method of action
            userStore
                .login(loginForm.value)
                .then(() => {
                    window.location.href = '/index';
                })
                .catch(() => {
                    loading.value = false;
                    getCode();
                });
        }
    });
}
const timeValue = 3;
const codeTime = ref(timeValue);
function handleFPCodeClick() {
    if (codeFlag.value) return;
    codeFlag.value = !codeFlag.value;
    setInterval(() => {
        if (codeTime.value > 1) {
            codeTime.value--;
        } else {
            clearInterval();
            codeTime.value = timeValue;
            codeFlag.value = false;
        }
    }, 1000);
}
</script>

<style lang="scss">
.fp-form-dialog {
    .wrapper {
        width: 100%;
        display: flex;
        justify-content: space-between;
        align-items: center;

        .el-button {
            width: 102px;
            min-width: 102px;
        }
    }

    .el-dialog__body {
        height: 316px !important;
    }

    .el-button {
        background: #2666fb;
        border-radius: 2px 2px 2px 2px;
        border: 1px solid #2666fb;
    }
}
</style>

<style scoped lang="scss">
.login-minimal {
    .login-captcha {
        display: flex;
        width: 100%;
        gap: 12px;
        align-items: center;
    }
    .captcha-refresh {
        flex: 0 0 100px;
        padding: 0;
        height: 34px;
        border: 1px solid #dcdfe6;
        background: white;
        cursor: pointer;
        img { width: 100%; height: 100%; }
    }
    height: 100%;
    width: 100%;
    overflow: auto;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f2f4f8;

    .login-wrap {
        width: 400px;
        display: flex;
        flex-direction: column;
    }

    .greeting {
        margin-bottom: 24px;

        .entry_period {
            font-size: 25px;
            font-weight: 400;
            margin-bottom: 6px;
            color: rgba(57, 63, 79, 1);
        }

        .entry_greeting {
            font-size: 12px;
            font-weight: 400;
            color: #888;
        }
    }

    .form-actions {
        display: flex;
        align-items: center;
        justify-content: space-between;
        cursor: pointer;
    }

    ::v-deep .el-form-item__content {
        justify-content: space-between;
    }

    ::v-deep .el-input--medium .el-input__inner {
        height: 40px;
        line-height: 40px;
        padding-left: 35px;
        border-radius: 4px;
        border: 1px solid #e6e6e6;
    }

    ::v-deep .el-input__icon.input-icon.svg-icon {
        height: 45px;
        width: 14px;
        margin-left: 5px;
    }

    ::v-deep .el-form-item__error {
        color: #ff4949;
        font-size: 12px;
        line-height: 1;
        position: absolute;
        top: 100%;
        left: 0;
    }

    ::v-deep .el-checkbox {
        padding: 0 !important;
    }

    .login-code-img {
        height: 100%;
        width: 102px;
    }

    .login-code {
        width: 104px;
        height: 34px;
        float: right;
    }

    ::v-deep .el-form-item {
        margin-bottom: 24px;
    }

    ::v-deep .el-input__prefix {
        top: 1px !important;
        left: 15px !important;
    }

    .iconfont {
        font-size: 17px !important;
    }

    .login-panel {
        box-sizing: border-box;
        width: 400px;
        height: 400px;
        background: #fff;
        box-shadow: -22px -22px 44px 0px rgba(255, 255, 255, 1),
            22px 22px 44px 0px rgba(217, 217, 217, 1);
        border-radius: 8px;
        padding: 0 38px;
        position: relative;
        overflow: hidden;

        ::v-deep .el-form-item {
            margin-bottom: 20px;
        }

        .titles {
            text-align: center;
            line-height: 65px;
            height: 65px;
            border-bottom: 1px solid rgba(0, 0, 0, 0.1);
            font-size: 18px;
            font-weight: bold;
            color: #333;
            margin-bottom: 30px;
        }

        .titles-bar {
            width: 65px;
            height: 2px;
            background: #2666fb;
            top: 66px;
            position: absolute;
            left: 50%;
            transform: translateX(-50%);
        }

        .code-class {
            width: 63%;
        }
    }

    ::v-deep .el-button--medium {
        padding: 12px 20px;
    }
}

@media screen and (max-width: 576px) {
    .login-minimal {
        .login-wrap {
            width: auto;
            padding: 0 14px;
        }

        .greeting {
            text-align: center;

            .entry_period {
                font-size: 16px;
            }
        }

        .login-panel {
            width: 100%;
            height: 360px;
            padding: 0 20px;
            box-shadow: 5px 5px 10px 2px #eee;

            .titles {
                margin: 0 0 20px 0;
            }

            .titles-bar {
                top: 48px;
            }

            .code-class {
                width: 55%;
            }
        }
    }
}

.app-container {
    padding: 0px;
    margin: 0px;
    background-color: #f2f4f8;
    min-height: 100%;
}
</style>
