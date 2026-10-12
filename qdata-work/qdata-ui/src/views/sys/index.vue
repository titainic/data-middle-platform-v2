<!--
  Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.

  This file is part of qData Data Middle Platform (Open Source Edition).

  qData is licensed under Apache License 2.0 with additional qData terms.
  You may use qData for commercial purposes, but you may not remove, hide,
  modify, or replace the qData logo, copyright notices, license notices,
  or attribution information without a separate commercial license.

  White-label use, OEM distribution, rebranding, or presenting qData as
  another product requires separate commercial authorization from
  Jiangsu Qiantong Technology Co., Ltd.

  Business License: https://community.qdata.tech/business/policy.html
  See the LICENSE file in the project root for full license information.
-->

<template>
  <div class="app-container stagingIndex">

    <!-- Important reminder for presentation environment -->

    <el-row :gutter="15">
      <el-col :xs="24" :sm="24" :md="24" :lg="24" class="home-gutter">
        <div class="userInfo module-1">
          <div class="info-main">
            <img :src="userStore.avatar" alt="" class="avatar" />
            <div class="info-con">
              <div class="info-con-name">{{ greeting }}，{{ nickName }} ，{{ message }}</div>
              <div class="info-con-desc">
                <span style="color: var(--el-color-primary)">{{ userStore.name }}</span>
                <el-divider direction="vertical" />
                {{ xljtcont }}
              </div>
            </div>
            <div class="info-btns">
              <!-- <a href="/user/profile" class=""> -->
              <el-button type="primary" class="info-btn-dft" plain size="large" @click="goprofile"
                style="background: #135afb; color: #fff; font-size: 14px">
                {{ td('sys.dashboard.personalCenter') }}
              </el-button>
              <el-button auto-insert-space @click="logout" type="primary" class="info-btn-dft info-btn-dfts" plain
                size="large" style="color: #135afb">
                {{ td('sys.dashboard.logout') }}
              </el-button>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="24" :md="18" :lg="18" class="home-gutter">
        <!-- Module 3 -->
        <p class="overview-scope">{{ overviewFailed ? overviewText.failed : overview.scope==='all' ? overviewText.all : overviewText.mine }}</p>
        <div class="module-3">
          <div class="module-item" v-for="(item, index) in module1" :key="index">
            <!-- <router-link to="/sy/index"> -->
            <div class="module-item-t">
              <div class="module-item-t-l">
                <div class="name">{{ item.name }}</div>
                <span>{{ item.value }}</span>
              </div>
              <img :src="item.img" class="module-item-t-r" />
            </div>
            <!-- </router-link> -->

            <div class="module-item-border"></div>
            <div class="module-item-data">
              <span class="data-label">{{ overviewText.recent }}</span>
              <span class="data-up">{{ item.recent ?? '—' }}</span>
            </div>
          </div>
        </div>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="24" :md="12" :lg="12">
            <div class="module-9 border-item">
              <div class="border-item-head">
                <span class="head-title">{{ td('sys.dashboard.apiCallTrend') }}</span>
                <!-- <el-link type="primary" :underline="false">See more </el-link> -->
              </div>
              <div class="border-item-body">
                <div class="chart-container" ref="module5ChartRef"></div>
              </div>
            </div>
          </el-col>
          <el-col :xs="24" :sm="24" :md="12" :lg="12">
            <div class="module-8 border-item">
              <div class="border-item-head">
                <span class="head-title">{{ overviewText.qualityTrend }}</span>
                <!-- <el-link type="primary" :underline="false">See more </el-link> -->
              </div>
              <div class="border-item-body">
                <div class="chart-container" ref="module8ChartRef"></div>
              </div>
            </div>
          </el-col>
        </el-row>
      </el-col>
      <el-col :xs="24" :sm="24" :md="6" :lg="6" class="home-gutter">
        <div class="border-item module-7">
          <div class="news">
            <div class="border-item">
              <div class="border-item-head">
                <span class="head-title">{{ td('sys.dashboard.quickEntry') }}</span>
                <router-link to="/"> </router-link>
              </div>
              <div class="border-item-body" style="padding: 0; display: flex;">
                <div class="all-entrance">
                  <div class="entrance-item" v-for="item in entranceList" :key="item.name" v-hasPermi="item.perm"
                    @click="routeTo(item.path, item.query)">
                    <div class="image">
                      <div class="icon-background" :class="item.color">
                        <img :src="item.icon" class="module-item-t-r" />
                      </div>
                    </div>
                    <div class="name">{{ item.name }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="24" :md="24" :lg="24">
        <div class="module-10 border-item">
          <div class="border-item-head">
            <span class="head-title">{{ overviewText.executions }}</span>
            <!-- <el-link type="primary" :underline="false">See more </el-link> -->
          </div>
          <div class="cards">
            <div v-for="item in statusList" :key="item.title" class="card">
              <div class="icon-title">
                <img :src="item.icon" class="module-item-t-r" />
                <div class="title-text">{{ item.name }}</div>
              </div>
              <div class="stats">
                <div class="stat-box normal-box">
                  <div class="num">{{ item.normal }}</div>
                  <div class="label">{{ overviewText.success }}</div>
                </div>

                <div class="stat-box abnormal-box">
                  <div class="num">{{ item.error }}</div>
                  <div class="label">{{ overviewText.failure }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

  </div>
</template>

<script setup name="Index">
import useUserStore from "@/store/system/user";
import useAppStore from "@/store/system/app";
import * as echarts from "echarts";
import { loginOut } from "@/api/system/sso-auth.js";
import { finishLogout } from "@/store/system/logoutFlow.mjs";
import { homeList } from "@/api/system/home.js";
import useDefaultLang from "@/composables/useDefaultLang";

import { useTimeGreeting } from '@/composables/useTimeGreeting'
const { greeting, message } = useTimeGreeting()
const { td, locale, tm } = useDefaultLang();

// Monitor language changes and re-render charts
watch(locale, () => {
  // Destroy existing chart instance
  chartIntances.forEach((instance) => {
    instance.dispose();
  });
  chartIntances.length = 0;
  // Reinitialize the chart
  renderOverviewCharts();
});

// eslint-disable-next-line no-unused-vars
import {
  onBeforeUnmount,
  onMounted,
  onActivated,
  computed,
  ref,
  watch,
  getCurrentInstance,
} from "vue";
import {
  Folder,
  Files,
  Operation,
  Timer,
  Collection,
  DataLine,
  Tickets,
} from "@element-plus/icons-vue";

let { proxy } = getCurrentInstance();
import { useRouter } from "vue-router";

const router = useRouter();
async function routeTo(link, query = {}) {
  if (link && link.indexOf("http") !== -1) {
    window.location.href = link;
    return;
  }

  if (link) {
    if (link === router.currentRoute.value.path) {
      window.location.reload();
    } else {
      try {
        await router.push({ path: link, query });
        // Refresh after successful jump
        window.location.reload();
      } catch (err) {
        console.error("Route navigation failed:", err);
      }
    }
  }
}

const getAssetsFile = (url) => {
  return new URL(`../../assets/images/system/index/${url}`, import.meta.url)
    .href;
};

const carousel = ref(null);


const prevSlide = () => {
  carousel.value.prev();
};
const overview=ref({totals:{},recent:{},days:[],apiCalls:[],qualitySuccess:[],qualityFailure:[],qualityRunning:[],executions:[],scope:'all'})
const overviewFailed=ref(false)
const overviewText=computed(()=>locale.value==='en-US'?{
  recent:'Added in 7 days',all:'Platform-wide statistics',mine:'Resources accessible to this account',failed:'Statistics unavailable. Reload to retry.',qualityTrend:'Quality task execution trend',executions:'Task executions in the last 7 days',success:'Succeeded',failure:'Failed',running:'Running',quality:'Quality inspection'
}:locale.value==='ja-JP'?{
  recent:'直近7日の追加',all:'プラットフォーム全体の統計',mine:'このアカウントがアクセスできるリソース',failed:'統計を取得できません。再読み込みしてください。',qualityTrend:'品質タスクの実行推移',executions:'直近7日のタスク実行',success:'成功',failure:'失敗',running:'実行中',quality:'品質チェック'
}:{recent:'近7天新增',all:'统计范围：全平台',mine:'统计范围：当前账号可访问的资源',failed:'统计暂不可用，请刷新页面重试',qualityTrend:'质量任务执行趋势',executions:'近7天任务执行',success:'成功',failure:'失败',running:'执行中',quality:'质量稽查'})
const executionMeta=[['integration','sys.dashboard.batchCollection','icon-batch.svg'],['development','sys.dashboard.dataDevelopment','icon-dev.svg'],['jobs','sys.dashboard.taskOrchestration','icon-task.svg'],['quality',null,'icon-db.svg']]
const statusList=computed(()=>executionMeta.map(([key,label,icon])=>{
  const data=overview.value.executions.find(item=>item.key===key)||{}
  return {name:label?td(label):overviewText.value.quality,icon:getAssetsFile(icon),normal:data.success??'—',error:data.failure??'—'}
}))
let overviewRequest
let overviewDisposed=false
function renderOverviewCharts(){
  if(overviewDisposed || !module5ChartRef.value || !module8ChartRef.value)return
  chartIntances.forEach(chart=>chart.dispose());chartIntances.length=0;initModule5();initModule8()
}
function refreshOverview(){
  if(overviewRequest)return overviewRequest
  overviewRequest=homeList().then(response=>{
    if(Number(response.code)!==200 || !response.data)throw new Error('Overview unavailable')
    overview.value=response.data;overviewFailed.value=false
  }).catch(()=>{overviewFailed.value=true}).finally(()=>{overviewRequest=null;renderOverviewCharts()})
  return overviewRequest
}
onActivated(refreshOverview)
onBeforeUnmount(()=>{overviewDisposed=true;chartIntances.forEach(chart=>chart.dispose())})

const nextSlide = () => {
  carousel.value.next();
};

const nickName = computed(() => {
    const name = userStore.nickName;
    const userId = userStore.id;
    if (userId === 1) {
        return td('common.texts.superAdmin');
    }
    return name;
});

const chartIntances = [];
// eslint-disable-next-line no-unused-vars
const appStore = useAppStore();
const userStore = useUserStore();
const module1=computed(()=>[
  ['integration','sys.dashboard.dataIntegrationTask','img-one.png'],
  ['development','sys.dashboard.dataDevTask','img-two.png'],
  ['jobs','sys.dashboard.dataJobTask','img-three.png'],
  ['assets','sys.dashboard.dataAsset','img-four.png'],
  ['apis','sys.dashboard.apiService','img-five.png']
].map(([key,label,img])=>({name:td(label),value:overview.value.totals[key]??'—',recent:overview.value.recent[key],img:getAssetsFile(img)})))

const entranceList = [
  {
    name: td('sys.dashboard.dataConnection'),
    path: "/da/datasource",
    query: { type: "0" },
    perm: ["da:dataSource:list"],
    color: "color-primary",
    icon: getAssetsFile("img-connect.png"),
  },
  {
    name: td('sys.dashboard.assetMap'),
    path: "/da/asset",
    query: {},
    perm: ["da:asset:list"],
    color: "color-pale-blue",
    icon: getAssetsFile("img-map.png"),
  },
  {
    name: td('sys.dashboard.dataIntegration'),
    path: "/dpp/task/integratioTask",
    query: {},
    TYPE: 1,
    perm: ["dpp:integratioTask:list"],
    color: "color-orange",
    icon: getAssetsFile("img-integration.png"),
  },
  {
    name: td('sys.dashboard.dataDevelopment'),
    path: "/dpp/task/developTask",
    query: {},
    TYPE: 3,
    perm: ["dpp:developTask:list"],
    icon: getAssetsFile("img-develop.png"),
  },
  {
    name: td('sys.dashboard.apiManagement'),
    path: "/ds/api",
    query: {},
    perm: ["ds:api:list"],
    color: "color-pale-blue",
    icon: getAssetsFile("img-api.png"),
  },
];

function goprofile() {
  proxy.$router.push("/user/profile"); // internal page path
}
//Get Chicken Soup for the Soul content
const xljtcont = ref("");
function getxljtcont() {
  let num = Math.floor(Math.random() * 9);
  let xljtlist = [
    { value: td('sys.dashboard.quote1') },
    { value: td('sys.dashboard.quote2') },
    { value: td('sys.dashboard.quote3') },
    { value: td('sys.dashboard.quote4') },
    { value: td('sys.dashboard.quote5') },
    { value: td('sys.dashboard.quote6') },
    { value: td('sys.dashboard.quote7') },
    { value: td('sys.dashboard.quote8') },
    { value: td('sys.dashboard.quote9') },
    { value: td('sys.dashboard.quote10') },
    { value: td('sys.dashboard.quote11') },
    { value: td('sys.dashboard.quote12') },
    { value: td('sys.dashboard.quote13') },
    { value: td('sys.dashboard.quote14') },
    { value: td('sys.dashboard.quote15') },
  ];
  xljtcont.value = xljtlist[num].value;
}

const module4ChartRef = ref(null);
function initModule4() {
  const intance = echarts.init(module4ChartRef.value, "macarons");
  let m2R2Data = [
    // {
    //     value: 335,
    //     legendname: 'Type 06',
    //     name: 'Type 06 335'
    //     // itemStyle: { color: '#fca4bb' }
    // },
    // {
    //     value: 335,
    //     legendname: 'Type 07',
    //     name: 'Type 07 335'
    //     // itemStyle: { color: '#f59a8f' }
    // },
    {
      value: 130,
      legendname: td('sys.dashboard.chart.type08'),
      name: td('sys.dashboard.chart.type08'),
      // itemStyle: { color: '#fdb301' }
    },
    {
      value: 150,
      legendname: td('sys.dashboard.chart.type09'),
      name: td('sys.dashboard.chart.type09'),
      // itemStyle: { color: '#57e7ec' }
    },
    {
      value: 100,
      legendname: td('sys.dashboard.chart.type10'),
      name: td('sys.dashboard.chart.type10'),
      // itemStyle: { color: '#cf9ef1' }
    },
    {
      value: 190,
      legendname: td('sys.dashboard.chart.type11'),
      name: td('sys.dashboard.chart.type11'),
      // itemStyle: { color: '#57e7ec' }
    },
    {
      value: 200,
      legendname: td('sys.dashboard.chart.type12'),
      name: td('sys.dashboard.chart.type12'),
      // itemStyle: { color: '#cf9ef1' }
    },
  ];

  let option = {
    title: [
      // {
      //     text: 'Title',
      //     textStyle: {
      //         fontSize: 16,
      //         color: 'black'
      //     },
      //     left: '2%'
      // },
      {
        text: "100%",
        // subtext: 12312 + 'piece',
        textStyle: {
          fontSize: 30,
          color: "rgba(0,0,0,0.65)",
          fontFamily: "Sharp",
        },
        // subtextStyle: {
        //     fontSize: 20,
        //     color: 'black'
        // },
        textAlign: "center",
        x: "34.5%",
        y: "43%",
      },
    ],
    tooltip: {
      trigger: "item",
      formatter: function (parms) {
        let str =
          parms.seriesName +
          "</br>" +
          parms.marker +
          "" +
          parms.data.legendname +
          "</br>" +
          td('common.quantity') + "：" +
          parms.data.value +
          "</br>" +
          td('common.proportion') + "：" +
          parms.percent +
          "%";
        return str;
      },
    },
    legend: {
      type: "scroll",
      orient: "vertical",
      left: "75%",
      align: "left",
      top: "10%",
      // top: 'middle',
      itemWidth: 8, // Set the width of the legend items
      itemHeight: 8, // Set the height of the legend item
      icon: "circle", // Set the legend icon to be circular
      // textStyle: {
      //     color: '#8C8C8C',
      //     fontSize: 14,
      //     lineHeight: 20 // Set the line height to ensure the text is vertically centered
      // },

      formatter: function (name) {
        // Find the corresponding item and calculate the percentage
        console.log(name, "============name");
        let total = 770;
        let item = m2R2Data.find((item) => item.name === name);
        // eslint-disable-next-line no-unused-vars
        let percent = item
          ? ((item.value / total) * 100).toFixed(2) + "%"
          : "0%";
        // return `${name} | ${percent} ${item.value}`; // Custom legend display
        // Customize colors using HTML syntax
        let arr = [
          "{a|" + name + "}",
          // '{b|' + '|   ' + percent + '}',
          // '{c|' + item.value + '}'
        ];
        return arr.join("  ");
        // return `${name}: <span style="color: red;">${percent}</span> (<span style="color: blue;">${percent}</span>)`;
      },
      textStyle: {
        lineHeight: 25, // Set line height to ensure text is vertically centered

        // add
        padding: [0, 0, 0, 0],
        rich: {
          a: {
            fontSize: 14,
            width: 46,
          },
          b: {
            fontSize: 14,
            width: 70,
            color: "#888888",
          },
          c: {
            fontSize: 14,
            color: "rgba(0,0,0,0.65)",
          },
        },
      },
      // height:250
    },
    series: [
      {
        name: td('sys.dashboard.chart.title'),
        type: "pie",
        center: ["35%", "50%"],
        radius: ["35%", "55%"],
        clockwise: false, //Whether the slices of the pie chart are arranged clockwise
        avoidLabelOverlap: false,
        // label: {
        //     normal: {
        //         // show: true,
        //         // position: 'outter',
        //         formatter: function (parms) {
        //             return parms.data.value;
        //         }
        //     }
        // },
        label: {
          formatter: "{d}%", // Show the name and proportion of each part
          position: "outside", // label position
        },
        itemStyle: {
          normal: {
            color: function (params) {
              let colorList = [
                {
                  c1: "#1E60FB",
                  c2: "#5D8EFE",
                },
                {
                  c1: "#6CD8D0",
                  c2: "#1DC7B5",
                },
                {
                  c1: "#F9D370",
                  c2: "#F7BD26",
                },
                {
                  c1: "#B28AE9",
                  c2: "#9358E3",
                },
                {
                  c1: "#EA7283",
                  c2: "#F53D57",
                },
              ];
              return new echarts.graphic.LinearGradient(1, 0, 0, 0, [
                {
                  //The first four parameters of the color gradient function represent the four positions respectively: left, bottom, right, and top.
                  offset: 0,
                  color: colorList[params.dataIndex].c1,
                },
                {
                  offset: 1,
                  color: colorList[params.dataIndex].c2,
                },
              ]);
            },
          },
        },
        data: m2R2Data,
      },
    ],
  };
  intance.setOption(option);
  chartIntances.push(intance);
  //  // Automatically update the chart size when the window size changes
  // window.addEventListener('resize', function() {
  //     intance.resize();
  // });
}
// Authentication mode
const authType = import.meta.env.VITE_APP_AUTH_TYPE;
function logout() {
  ElMessageBox.confirm(td('sys.dashboard.confirmLogout'), td('common.message.prompt'), {
    confirmButtonText: td('common.button.confirm'),
    cancelButtonText: td('common.button.cancel'),
    type: 'warning'
  })
    .then(async () => {
      await finishLogout(authType === 'sso' ? loginOut : null, () => userStore.logOut(), () => {
        location.href = '/index';
      });
    })
    .catch(() => { });
}
const module5ChartRef = ref(null);
function initModule5() {
  const intance = echarts.init(module5ChartRef.value, "macarons");
  intance.setOption({
    grid: {
      top: "20%",
      bottom: 40,
      right: "4%",
    },
    legend: {
      show: false,
      itemGap: 50,
      icon: "circle",
      itemWidth: 8,
      itemHeight: 8,
      right: "5%",
      top: "0%",
      textStyle: {
        color: "rgba(0,0,0,0.65)",
        fontSize: 14,
        lineHeight: 30,
        fontFamily: "PingFangSC, PingFang SC",
      },
    },
    xAxis: {
      type: "category",
      data: overview.value.days.map(day=>day.slice(5)),
      axisTick: {
        show: false,
      },
      axisLine: {
        show: true,
        lineStyle: {
          color: "rgba(0,0,0,0.15)",
          width: 1,
          type: "solid",
        },
      },
      axisLabel: {
        margin: 14,
        fontSize: 12,
        color: "rgba(0,0,0,0.65)",
        fontFamily: "PingFangSC, PingFang SC",
      },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      name: `${td('home.echart.unit')}: ${td('home.echart.times')}`,
      nameLocation: "end",

      nameTextStyle: {
        color: "rgba(0,0,0,0.85)",
        fontSize: 14,
        padding: [0, 0, 10, -20],
        fontFamily: "PingFangSC, PingFang SC",
      },
    },
    series: [
      {
        type: "bar",
        name: td('sys.dashboard.chart.callCount'),
        barWidth: 20,
        itemStyle: {
          borderRadius: [2, 2, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            {
              offset: 0,
              color: "#5D8EFE",
            },
            {
              offset: 1,
              color: "#1E60FB",
            },
          ]),
        },
        data: overview.value.apiCalls,
      },
    ],
  });
  chartIntances.push(intance);
}

const module8ChartRef = ref(null);

function initModule8() {
  const instance = echarts.init(module8ChartRef.value, "macarons");

  instance.setOption({
    legend: {
      show: true,
      itemGap: 10, // Reduce legend spacing
      data: [overviewText.value.success, overviewText.value.failure, overviewText.value.running],
      icon: "circle",
      itemWidth: 6,
      itemHeight: 6,
      right: "4%",
      top: "2%",
      textStyle: {
        color: "rgba(0,0,0,0.65)",
        fontSize: 14,
        lineHeight: 30,
        fontFamily: "PingFangSC, PingFang SC",
        padding: [5, 0, 0, 5],
      },
      selected: {
        [overviewText.value.success]: true,
        [overviewText.value.failure]: true,
        [overviewText.value.running]: true,
      },
    },
    grid: {
      top: "20%",
      bottom: 40,
      right: "4%",
    },
    xAxis: {
      type: "category",
      data: overview.value.days.map(day=>day.slice(5)),
      axisTick: {
        show: false,
      },
      splitLine: {
        show: false,
      },
      axisLine: {
        show: true,
        lineStyle: {
          color: "rgba(0,0,0,0.15)",
          width: 1,
          type: "solid",
        },
      },
      axisLabel: {
        margin: 14,
        fontSize: 12,
        color: "rgba(0,0,0,0.65)",
        fontFamily: "PingFangSC, PingFang SC",
      },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      name: `${td('home.echart.unit')}: ${td('home.echart.times')}`,
      nameLocation: "end",

      nameTextStyle: {
        color: "rgba(0,0,0,0.85)",
        align: "left",
        fontSize: 14,
        padding: [0, 0, 10, -38],
        fontFamily: "PingFangSC, PingFang SC",
      },
    },
    series: [
      {
        name: overviewText.value.success,
        type: "line",
        data: overview.value.qualitySuccess,
        symbolSize: 8,
        itemStyle: {
          color: "#427afd",
          borderColor: "#427afd",
          borderWidth: 1,
        },
        lineStyle: {
          color: "#5285fd",
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(204, 220, 254,1)" },
            { offset: 1, color: "rgba(204, 220, 254,0)" },
          ]),
        },
      },
      {
        name: overviewText.value.failure,
        type: "line",
        data: overview.value.qualityFailure,
        symbolSize: 8,
        itemStyle: {
          color: "#fa7f6f",
          borderColor: "#fa7f6f",
          borderWidth: 1,
        },
        lineStyle: {
          color: "#fa7f6f",
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(250, 127, 111,1)" },
            { offset: 1, color: "rgba(250, 127, 111,0)" },
          ]),
        },
      },
      {
        name: overviewText.value.running,
        type: "line",
        data: overview.value.qualityRunning,
        symbolSize: 8,
        itemStyle: {
          color: "#72cddc",
          borderColor: "#72cddc",
          borderWidth: 1,
        },
        lineStyle: {
          color: "#72cddc",
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(114, 205, 220,1)" },
            { offset: 1, color: "rgba(114, 205, 220,0)" },
          ]),
        },
      },
    ],
  });

  chartIntances.push(instance);
}

function chartIntancesResize() {
  console.log(chartIntances);
  chartIntances.forEach((intance) => {
    intance.resize();
  });
}

window.addEventListener("resize", chartIntancesResize);

onBeforeUnmount(() => {
  window.removeEventListener("resize", chartIntancesResize);
});

// Get the current instance
const instance = getCurrentInstance();

// Remove event listener when component is destroyed
onBeforeUnmount(() => {
  instance.appContext.config.globalProperties.$bus.off(
    "getsidebarStatus",
    callback
  );
});

onMounted(() => {
  refreshOverview();
  getxljtcont();
  instance.appContext.config.globalProperties.$bus.on(
    "getsidebarStatus",
    () => {
      window.addEventListener("resize", chartIntancesResize);
    }
  );
});
</script>

<style scoped lang="scss">
.overview-scope {margin:0 0 8px;color:#606266;font-size:12px;}
.home-gutter {
  position: relative;

  .monitor-status {
    padding: 20px;

    .status-row {
      margin-bottom: -20px; // Offset the effect of the last line of gutter

      .el-col {
        margin-bottom: 20px;
      }
    }

    .status-card {
      border-radius: 2px;
      min-height: 50px;
      display: flex;
      flex-direction: column;

      .card-body {
        display: flex;
        align-items: center;
        height: 100%;
        gap: 16px;

        @media (max-width: 768px) {
          flex-direction: column;
          align-items: stretch;
          gap: 12px;
        }
      }

      .left-info {
        background-color: #eaf3fe;
        flex: 0 0 30%;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 10px 0;
        min-height: 80px;

        @media (max-width: 768px) {
          flex: none;
          width: 100%;
          padding: 16px 0;
          min-height: 40px;
        }

        .icon {
          color: #409eff;
          margin-bottom: 6px;
        }

        .name {
          font-size: 13px;
          font-weight: bold;
          color: #303133;
          text-align: center;
        }
      }

      .divider {
        width: 1px;
        background-color: #8fc7ff;
        margin: 0 20px;
        align-self: stretch;

        @media (max-width: 768px) {
          width: 100%;
          height: 1px;
          margin: 0;
        }
      }

      .right-status {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 20px;

        @media (max-width: 768px) {
          flex: none;
          width: 100%;
          flex-direction: row;
          justify-content: space-around;
          gap: 12px;
        }

        .status-line {
          display: flex;
          align-items: center;

          .count {
            width: 40px;
            height: 18px;
            font-size: 12px;
            font-weight: bold;
            line-height: 18px;
            text-align: center;
            color: #fff;
            margin-right: 6px;

            &.normal {
              background-color: #2ad14d;
            }

            &.error {
              background-color: #fd0201;
            }
          }

          .label {
            font-size: 13px;
            font-weight: bold;
            color: #5a5e66;
            text-align: center;
          }
        }
      }
    }
  }

  .news {
    height: 245px;

    .border-item-body {
      display: block;
    }

    .toAll {
      font-family: PingFangSC-Regular;
      font-size: 14px;
      color: #262626;
      font-weight: 400;

      &:hover {
        color: #0f62ff;
      }
    }

    .all-entrance {
        width: 100%;
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      .entrance-item {
        padding: 10px;
        text-align: center;

        .name {
          margin-top: 5px;
          font-family: PingFangSC, PingFang SC;
          font-weight: 400;
          font-size: 14px;
          color: rgba(0, 0, 0, 0.85);
          font-style: normal;
          line-height: 16px;
        }

        .image {
          height: 44px;
          display: flex;
          justify-content: center;

          // Icon rounded square background
          .icon-background {
            width: 40px;
            height: 40px;
            border-radius: 6px;
            position: relative;
          }

          // icon
          .icon {
            display: flex;
            justify-content: center;
            align-items: center;
            font-size: 24px;
            color: #fff;
            line-height: 40px;
          }

          // Background color: main color
          .color-primary {
            background-image: linear-gradient(to bottom right,
                #2162fc 30%,
                #4f84fd 80%);
          }

          // Background color: orange
          .color-orange {
            background-image: linear-gradient(to bottom right,
                #f59040 30%,
                #f9bd77 90%);
          }

          // Background color: light blue
          .color-pale-blue {
            background-image: linear-gradient(to bottom right,
                #348bf2 10%,
                #63abff 60%);
          }

          // Background color: pink
          .color-pink {
            background-image: linear-gradient(to bottom right,
                #fb6594 20%,
                #fc92bb 80%);
          }
        }
      }
    }
  }
}

.work-time-div {
  width: 85%;
  height: 99%;
  position: absolute;
  /* top: 20px; */
  right: 20px;
  background-color: #ffffff;
  /* color: white; */
  /* font-size: 18px; */
  /* font-weight: bold; */
  /* padding: 30px 30px; */
  border-radius: 2px;
  text-align: center;
  /* cursor: pointer; */
  /* box-shadow: 0 4px 8px rgba(0, 0, 0, 0.2); */
  /* transition: transform 0.3s, box-shadow 0.3s;
          }

          .work-time-div:hover {
              /*transform: scale(1.05);*/
  /*box-shadow: 0 6px 12px rgba(0, 0, 0, 0.3);*/
}

.work-time-div:active {
  transform: scale(1);
}

.stagingIndex {
  min-width: 1290px;
  max-width: 100%;
  // width: 100%;
  height: 100%;
  // padding: 24px;
  background: #f0f2f5;

  ::v-deep .el-carousel {
    height: 100%;

    .el-carousel__container {
      height: 100%;
    }

    .el-carousel__arrow--left {
      display: none;
    }

    .el-carousel__arrow--right {
      display: none;
    }
  }

  :deep(.el-dialog__body) {
    padding: 20px !important;
  }
}

.module-4,
.module-5,
.module-8,
.module-9 {
  height: 350px !important;
}

.module-10 {
  height: 254px !important;

  .monitor-status {
    padding: 10px 20px;
  }

  .title {
    border-left: 3px solid #409eff;
    padding-left: 8px;
    margin-bottom: 12px;
    color: #409eff;
    font-weight: 600;
    font-size: 16px;
  }

  .cards {
    display: flex;
    // flex-wrap: wrap;
    flex-wrap: nowrap;
    gap: 16px;
    padding: 16px;
  }

  .card {
    flex: 1;
    min-width: 220px;
    max-width: calc(25% - 13px);
    height: 170px;
    box-sizing: border-box;
    background: #fff;
    border-radius: 2px;
    border: 1px solid rgba(0, 0, 0, 0.06);
    padding: 16px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    align-items: center;

    .icon-title {
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(29, 96, 251, 0.04);
      border-radius: 2px;
      padding: 4px 8px;
      gap: 10px;
      width: 100%;
      height: 40px;

      .icon {
        width: 30px;
        height: 30px;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .title-text {
        font-size: 14px;
        font-weight: 600;
        white-space: nowrap;
        font-family: REEJI-HongGuangGB, REEJI-HongGuangGB;
        font-weight: bold;
        color: #2666fb;
      }
    }

    .stats {
      display: flex;
      gap: 15px;
      justify-content: center;
      width: 100%;
      box-sizing: border-box;

      .stat-box {
        flex: 1;
        height: 82px;
        border: 1px solid rgba(0, 0, 0, 0.04);
        padding: 15px 30px;
        display: flex;
        flex-direction: column;
        align-items: center;
        border-radius: 2px;
        background: #fff;

        .num {
          font-size: 20px;
          font-weight: 500;
          margin-bottom: 5px;
          line-height: 1;
          font-family: YouSheBiaoTiHei;
          font-size: 24px;
          color: #40cfc1;
        }

        .label {
          font-family: PingFangSC, PingFang SC;
          font-weight: 500;
          font-size: 14px;
          width: 28px;
          color: rgba(0, 0, 0, 0.45);
        }
      }

      .normal-box .num {
        color: #3dc47f;
      }

      .abnormal-box .num {
        color: #f8b34a;
      }
    }
  }
}

.module-6,
.module-7 {
  height: 250px !important;
  //   box-shadow: 1px 1px 3px rgba(0, 0, 0, 0.2);
}

.module-report {
  margin-bottom: 15px;
  height: 60px !important;
}

.module-report .report-button {
  width: 100%;
  height: 60px;
  font-size: 22px;
  border-radius: 2px;
}

.home-gutter {
  margin-bottom: 15px;
}

.userInfo {
  height: 150px;
  padding: 35px 60px 0 32px;
  border-radius: 2px;
  justify-content: space-between;
  align-items: center;
  background: url(@/assets/images/common/bg-content.png) no-repeat center;
  background-size: 100% 100%;
  //   box-shadow: 1px 1px 3px rgba(0, 0, 0, 0.2);
}

.userInfo .menu {
  margin-bottom: 16px;
}

.userInfo .menu .el-breadcrumb__inner.is-link {
  font-family: PingFang SC;
  font-size: 14px;
  color: #888888;
  line-height: 22px;
  font-weight: 400;
}

.userInfo .menu .el-breadcrumb__inner {
  font-family: PingFang SC;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.65);
  text-align: left;
  line-height: 22px;
  font-weight: 400;
}

.userInfo .menu .el-breadcrumb__separator {
  font-size: 14px;
  color: #888888;
}

.userInfo .info-main {
  min-width: 100%;
  display: flex;
  position: relative;
  flex-shrink: 0;
}

.userInfo .info-main .avatar {
  width: 72px;
  height: 72px;
  margin-right: 24px;
  border-radius: 50%;
  border-radius: 50%;
  // border: 3px solid #fff;
  object-fit: cover;
  // box-shadow: 0 0 4px rgba(0, 0, 0, 0.1); /* Optional, add some shadow to enhance the layering */
}

.userInfo .info-main .info-con {
  height: 72px;
  display: flex;
  flex-direction: column;
  justify-content: space-around;
}

.userInfo .info-main .info-con .info-con-name {
  font-family: PingFang SC;
  font-size: 20px;
  color: rgba(0, 0, 0, 0.85);
  line-height: 28px;
  font-weight: 600;
}

.userInfo .info-main .info-con .info-con-desc {
  font-family: PingFang SC;
  font-size: 14px;
  color: #888888;
  line-height: 22px;
  font-weight: 400;
}

.userInfo .info-main .info-btns {
  position: absolute;
  top: 18px;
  right: 0;
  font-family: PingFang SC;
  font-size: 14px;
  font-weight: 500;
}

.userInfo .info-main .info-btns .info-btn {
  width: 100px;
  height: 40px;
  border-radius: 4px !important;
  border: none !important;
}

.userInfo .info-main .info-btns .info-btn-dft {
  font-family: PingFangSC, PingFang SC !important;
  font-weight: 500 !important;
  height: 40px;
  border-radius: 2px !important;
  margin-left: 20px;
}

.userInfo .info-main .info-btns .info-btn-dfts {

  &:hover,
  &:focus,
  &:active {
    color: #135afb !important;
    border-color: #135afb !important;
    background-color: transparent !important;
    box-shadow: none !important;
  }
}

.module-2 {
  height: 150px;
  // background-color: #fff;
  border-radius: 2px;
  text-align: center;
  //   box-shadow: 1px 1px 3px rgba(0, 0, 0, 0.2);

  img {
    height: 100%;
  }
}

.module-2 #mobile12 .wtleft {
  width: 30%;
}

.module-3 {
  display: flex;

  justify-content: space-between;
  height: 150px;
  margin-bottom: 15px;
}

.module-3 .module-item {
  // width: 18.3%;
  // min-width: 185px;
  flex: 1;
  height: 100%;
  border-radius: 2px;
  padding: 22px 24px;
  background: #fff;
  margin-right: 15px;
  //   box-shadow: 1px 1px 3px rgba(0, 0, 0, 0.2);
}

.module-3 .module-item:last-of-type {
  margin-right: 0px;
}

.module-3 .module-item .module-item-t {
  display: flex;

  align-items: flex-end;

  justify-content: space-between;
}

.module-3 .module-item .module-item-t .module-item-t-l {
  font-family: PingFang SC;
  font-size: 14px;
  color: #888888;
  line-height: 22px;
  font-weight: 400;
}

.module-3 .module-item .module-item-t .module-item-t-l .name {
  margin-bottom: 10px;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
  font-family: PingFangSC, PingFang SC;
  font-weight: 400;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.45);
  line-height: 22px;
  text-align: left;
  font-style: normal;
}

.module-3 .module-item .module-item-t .module-item-t-l span {
  // font-family: Sharp;
  // font-size: 30px;
  color: #000;
  font-family: YouSheBiaoTiHei;
  font-size: 36px;
  color: rgba(0, 0, 0, 0.85);
}

.module-3 .module-item .module-item-t .module-item-t-r {
  width: 48px;
  height: 48px;
}

.module-3 .module-item .module-item-border {
  height: 1px;
  background: #e2e2e2;
  margin: 20px 0 14px;
}

.module-3 .module-item .module-item-data {
  display: flex;
  align-items: center;
  color: rgba(0, 0, 0, 0.65);
  font-size: 14px;

  .data-label {
    font-family: PingFangSC, PingFang SC;
    font-weight: 400;
    color: rgba(0, 0, 0, 0.65);
  }
}

.module-3 .module-item .module-item-data .data-up {
  font-size: 14px;
  margin-left: 8;
  margin-right: 4;
  // color: #000;
  color: rgba(0, 0, 0, 0.85);
  position: relative;
}

.module-3 .module-item .module-item-data .data-up:after {
  content: "";
  position: absolute;
  top: -1px;
  right: -20px;
  width: 0;
  height: 12px;
  border: 6px solid #f5222d;
  border-color: transparent transparent #f5222d transparent;
}

.module-3 .module-item .module-item-data .data-down {
  color: rgba(0, 0, 0, 0.85);
  font-size: 14px;
  margin-left: 8;
  margin-right: 10;
  position: relative;
}

.module-3 .module-item .module-item-data .data-down:after {
  content: "";
  position: absolute;
  top: 5px;
  right: -20px;
  width: 0;
  height: 12px;
  border: 6px solid #f5222d;
  border-color: #60f522 transparent transparent transparent;
}

.module-3 .module-item .module-item-data .data-equal {
  font-size: 14px;
  margin: 0 10px;
  color: #000;
  position: relative;
}

.border-item {
  width: 100%;
  height: 100%;
  background: #fff;
  border-radius: 2px;
}

.border-item .border-item-head {
  height: 50px;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e8e8e8;

  :deep(.el-link__inner) {
    color: rgba(0, 0, 0, 0.85) !important;
    font-family: PingFangSC, PingFang SC;
    font-weight: 400;
    font-size: 14px;
  }
}

.border-item .border-item-head .head-title {
  font-size: 16px;
  color: var(--el-color-primary);
  display: flex;
  align-items: center;
  font-family: PingFangSC, PingFang SC;
  font-weight: 500;
}

.border-item .border-item-head .head-title-seach {
  cursor: pointer;
  font-size: 14px;
  color: #0f62ff;
  line-height: 0px;
  font-style: normal;
}

.border-item .border-item-head .head-title:before {
  display: inline-block;
  content: "";
  width: 3px;
  height: 20px;
  border-radius: 2px 2px 2px 2px;
  background: var(--el-color-primary);
  margin-right: 10px;
}

.border-item .border-item-body {
  height: calc(100% - 50px);
  position: relative;
}

.border-item .border-item-body .chart-container {
  height: 100%;
}

.border-item .border-item-body .border-item-order {
  display: flex;
  justify-content: space-between;
  padding: 12px 20px 0 20px;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.65);
  line-height: 22px;
  font-weight: 400;
}

.border-item .border-item-body .border-item-order .name-span {
  width: 225px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.border-item .border-item-body .border-item-order .name-span:hover {
  color: #2666fb;
}

.border-item .border-item-body .border-item-order .time-span {
  margin-left: 20px;
  width: 60px;
  min-width: 60px;
  height: 22px;
  font-size: 14px;
  color: #888888;
  line-height: 22px;
}

.border-item .border-item-body .border-item-order .top {
  width: 50px;
  height: 22px;
  background: rgba(255, 0, 25, 0.06);
  border-radius: 2px;
  color: #ff0019;
}

.border-item .border-item-body .border-item-order .hui-span {
  background: #f0f2f5 !important;
  color: rgba(0, 0, 0, 0.65) !important;
}

.module3-item {
  background-color: #f7f7f7;
}

.module-6 {
  .border-item-body {
    overflow-y: auto;
    overflow-x: hidden;
    padding: 12px 0px;
  }

  .module-item {
    display: flex;
    align-items: center;
    padding: 6px 20px;
    cursor: pointer;
  }

  // .value,
  // .title {
  //     margin: 0 10px;
  // }

  .value {
    margin: 0 0 0 10px;
    font-size: 14px;
    white-space: nowrap;
    text-overflow: ellipsis;
    overflow: hidden;
    flex: 1;
    font-size: 14px;
    color: rgba(0, 0, 0, 0.85);
    font-weight: 400;
  }

  .time {
    width: 66px;
    text-align: right;
    font-size: 14px;
    color: rgba(0, 0, 0, 0.45);
    white-space: nowrap;
  }
}

.module-7 {
  .border-item-body {
    padding: 21px 0px;
    display: flex;
    align-items: center;
    justify-content: center;

    .carouselcont {
      height: 100%;
      width: calc(100% - 84px);
      margin-left: 8px;
      margin-right: 8px;
    }

    .qhbut {
      width: 21px;
      height: 21px;
      background: var(--el-color-primary);
      border-radius: 50%;
      border: 0px;
      color: #ffffff;
      text-align: center;
    }
  }

  .module-item {
    height: 100%;
    // background-color: #f6f6f6;
    // width: 100%;
    // height: 100%;
    // display: flex;
    // justify-content: center;
  }

  // ::v-deep {
  //     // .el-carousel__arrow {
  //     //     width: 21px;
  //     //     height: 21px;
  //     // }
  //     .el-carousel__arrow--left {
  //         display: none;
  //     }
  //     .el-carousel__arrow--right {
  //         display: none;
  //     }
  // }
}

.module-9 {

  // ::v-deep .el-table {
  //     thead .el-table__cell.is-leaf {
  //         background-color: #fff !important;
  //     }
  // }
  ::v-deep {

    // List header
    .el-table thead {
      height: 53px;

      .el-table__cell.is-leaf {
        background: rgba(19, 90, 251, 0.04) !important;
        // border-radius: 4px 4px 0px 0px;
      }
    }
  }
}

.module-9 {
  // .border-item-body {
  //   padding: 15px 34px 15px 34px;
  // }

  .table-column-code {
    font-size: 14px;
    color: #135afb;
  }
}

</style>
<style lang="scss" scoped>
@media screen and (max-width: 1280px) {}

@media screen and (max-width: 992px) {
  .stagingIndex {
    min-width: 100%;
    padding: 12px;

    .userInfo {
      height: 100px;
      padding: 15px 15px;

      .info-main {
        align-items: center;

        .avatar {
          width: 48px;
          height: 48px;
          margin-right: 12px;
        }

        .info-con {
          .info-con-name {
            font-size: 14px;
            line-height: 20px;
          }

          .info-con-desc {
            font-size: 10px;
            line-height: 20px;
          }
        }
      }

      .info-btns {
        display: none;
      }
    }

    .module-3 {
      flex-direction: column;
      height: auto;
    }

    .module-3 .module-item {
      width: 100%;
      margin-bottom: 15px;
    }
  }

  .module-4 {
    min-height: 480px !important;
  }

  .module-10 {
    height: auto !important;

    .cards {
      flex-wrap: wrap !important;
      gap: 12px;
    }

    .card {
      max-width: calc(50% - 8px); // 2 per row
    }
  }
}

@media screen and (max-width: 768px) {
  .stagingIndex {
    min-width: 100%;
    padding: 12px;

    .userInfo {
      height: 100px;
      padding: 15px 15px;

      .info-main {
        align-items: center;

        .avatar {
          width: 48px;
          height: 48px;
          margin-right: 12px;
        }

        .info-con {
          .info-con-name {
            font-size: 14px;
            line-height: 20px;
          }

          .info-con-desc {
            font-size: 10px;
            line-height: 20px;
          }
        }
      }

      .info-btns {
        display: none;
      }
    }

    .module-3 {
      flex-direction: column;
      height: auto;
    }

    .module-3 .module-item {
      width: 100%;
      margin-bottom: 15px;
    }
  }

  .module-4 {
    min-height: 1150px !important;
  }

  .module-10 {
    height: auto !important;

    .cards {
      flex-wrap: wrap !important;
    }
  }

}

@media screen and (max-width: 576px) {
  .stagingIndex {
    min-width: 100%;
    padding: 12px;

    .userInfo {
      height: 100px;
      padding: 15px 15px;

      .info-main {
        align-items: center;

        .avatar {
          width: 48px;
          height: 48px;
          margin-right: 12px;
        }

        .info-con {
          .info-con-name {
            font-size: 14px;
            line-height: 20px;
          }

          .info-con-desc {
            font-size: 10px;
            line-height: 20px;
          }
        }
      }

      .info-btns {
        display: none;
      }
    }

    .module-3 {
      flex-direction: column;
      height: auto;
    }

    .module-3 .module-item {
      width: 100%;
      margin-bottom: 15px;
    }
  }

  .module-4 {
    min-height: 1140px !important;
  }

  .module-10 {
    height: auto !important;

    .cards {
      flex-direction: column; // Change to one column
      flex-wrap: wrap !important;
      gap: 12px;
    }

    .card {
      max-width: 100%;
      width: 100%;
    }

    .stats {
      flex-direction: column;
      gap: 10px;

      .stat-box {
        width: 100%;
        padding: 12px;
      }
    }
  }
}
</style>
