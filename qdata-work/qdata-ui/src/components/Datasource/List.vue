<template>
    <el-select v-bind="$attrs" @change="handleDatasourceChange">
        <el-option
            v-for="source in store.datasources"
            :key="source.id"
            :label="source.datasourceName"
            :value="source.id"
            :disabled="source.disabled"
        />
    </el-select>
</template>

<script setup name="DatasourceList">
    import { reactive, watch } from 'vue';
    import { getDatasourceData, getAvailableDatasource } from './utils';
    import useUserStore from '@/store/system/user';

    const props = defineProps({
        flag: {
            type: String
        },
        data: {
            type: Array
        },
        project: {
            type: Boolean
        }
    });

    const emits = defineEmits(['change']);

    const userStore = useUserStore();

    const store = reactive({
        types: [],
        datasources: []
    });

    let loadVersion=0;
    function initDatasourceData() {
        const version=++loadVersion;
        if (Array.isArray(props.data)) {
            store.datasources = props.data;
            return;
        }
        const params = {};
        params.pageNum = 1;
        params.pageSize = 9999;
        if (props.project) {
            params.projectId = userStore.projectId;
            params.projectCode = userStore.projectCode;
        }
        getDatasourceData(params).then((data) => {
            if(version===loadVersion)store.datasources = getAvailableDatasource(data, props.flag);
        });
    }

    function handleDatasourceChange(id) {
        const source = store.datasources.find((item) => item.id === id);
        emits('change', id, source);
    }

    watch(()=>[props.data,props.flag,props.project,props.project?userStore.projectId:null,props.project?userStore.projectCode:null],initDatasourceData,{immediate:true});
</script>
