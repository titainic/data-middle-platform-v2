/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

import request from '@/utils/request'

// Query tag management category management list
export function listAttTagCat(query) {
    return request({
        url: '/att/tagCat/list',
        method: 'get',
        params: query
    })
}

// Query tag management category management details
export function getAttTagCat(id) {
    return request({
        url: '/att/tagCat/' + id,
        method: 'get'
    })
}

// Added tag management category management
export function addAttTagCat(data) {
    return request({
        url: '/att/tagCat',
        method: 'post',
        data: data
    })
}

// Modify tag management category management
export function updateAttTagCat(data) {
    return request({
        url: '/att/tagCat',
        method: 'put',
        data: data
    })
}

// Delete tag management category management
export function delAttTagCat(ids) {
    const input = Array.isArray(ids) ? ids : [ids];
    if (input.some(id => typeof id === 'number' && !Number.isSafeInteger(id))) {
        return Promise.reject(new Error('请选择有效的标签类目ID'));
    }
    const values = [...new Set(input.map(String))];
    if (!values.length || values.some(id => !/^[1-9]\d*$/.test(id) ||
        BigInt(id) > 9223372036854775807n)) {
        return Promise.reject(new Error('请选择有效的标签类目ID'));
    }
    return request({
        url: '/att/tagCat/' + values.join(','),
        method: 'delete'
    })
}
