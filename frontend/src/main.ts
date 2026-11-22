import { createApp } from 'vue'
import {
  ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElTag, ElTree,
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { makeRouter } from './router'
import './style.css'

const app = createApp(App)
for (const component of [ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElTag, ElTree]) {
  app.component(component.name!, component)
}
app.use(makeRouter())
app.mount('#app')
