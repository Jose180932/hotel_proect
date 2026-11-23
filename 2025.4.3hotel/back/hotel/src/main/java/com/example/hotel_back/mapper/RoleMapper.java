package com.example.hotel_back.mapper;

import com.example.hotel_back.Pojo.Entity.Role;
import com.example.hotel_back.Pojo.VO.RoleNameVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色只读 Mapper（角色管理功能已下线，仅保留查询）
 */
@Mapper
public interface RoleMapper {

    @Select("select * from role where id = #{id}")
    Role getById(Long id);

    /**
     * 启用状态的角色列表（SQL 见 RoleMappper.xml）
     */
    List<RoleNameVO> getAllRoles();
}
