package com.example.hotel_back.Service;

import com.example.hotel_back.Pojo.VO.RoleNameVO;

import java.util.List;

/**
 * 角色只读服务：
 * 员工表单需要选择职位，因此保留角色列表查询；
 * 角色的新增/修改/分页管理已下线（避免管理员给自己改角色）。
 */
public interface RoleService {

    /**
     * 获取启用状态的角色列表（用于员工表单下拉）
     */
    List<RoleNameVO> getAllRoles();
}
