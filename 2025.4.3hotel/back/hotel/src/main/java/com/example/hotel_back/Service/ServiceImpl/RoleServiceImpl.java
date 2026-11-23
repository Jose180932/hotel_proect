package com.example.hotel_back.Service.ServiceImpl;

import com.example.hotel_back.Pojo.VO.RoleNameVO;
import com.example.hotel_back.Service.RoleService;
import com.example.hotel_back.mapper.RoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleMapper roleMapper;

    @Override
    public List<RoleNameVO> getAllRoles() {
        return roleMapper.getAllRoles();
    }
}
