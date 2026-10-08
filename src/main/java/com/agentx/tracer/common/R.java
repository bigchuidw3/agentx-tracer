package com.agentx.tracer.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * 业务状态码约定（参考 HTTP 语义）：
 * - 2xx 成功
 * - 3xx 业务告警（部分成功、需用户注意但非错误）
 * - 4xx 客户端错误（参数/认证/权限/资源不存在）
 * - 5xx 服务端错误
 *
 * 使用方式：
 * R.ok(data)                    // 成功带数据
 * R.fail("xxx 失败")            // 服务端错误
 * R.paramError("参数缺失: id")  // 参数错误
 * R.notFound("skill 不存在")    // 资源不存在
 */
@Data
@Schema(name = "统一响应体", description = "接口统一返回结构")
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成功
     */
    public static final int SUCCESS = 200;
    /**
     * 业务告警（部分成功、需用户注意但非错误）
     */
    public static final int WARN = 300;
    /**
     * 参数错误
     */
    public static final int PARAM_ERROR = 400;
    /**
     * 未认证（未登录或 token 失效）
     */
    public static final int UNAUTHORIZED = 401;
    /**
     * 无权限
     */
    public static final int FORBIDDEN = 403;
    /**
     * 资源不存在
     */
    public static final int NOT_FOUND = 404;
    /**
     * 服务端错误（兜底）
     */
    public static final int FAIL = 500;

    /**
     * 成功默认提示
     */
    private static final String DEFAULT_SUCCESS_MSG = "操作成功";
    /**
     * 失败默认提示
     */
    private static final String DEFAULT_FAIL_MSG = "操作失败";

    @Schema(description = "业务状态码，200 表示成功", example = "200")
    private int code;

    @Schema(description = "业务提示信息", example = "操作成功")
    private String msg;

    @Schema(description = "响应数据")
    private T data;

    private R() {
    }

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(SUCCESS, DEFAULT_SUCCESS_MSG, null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, DEFAULT_SUCCESS_MSG, data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(SUCCESS, msg, data);
    }

    public static <T> R<T> fail() {
        return new R<>(FAIL, DEFAULT_FAIL_MSG, null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(FAIL, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> paramError(String msg) {
        return new R<>(PARAM_ERROR, msg, null);
    }

    public static <T> R<T> unauthorized() {
        return new R<>(UNAUTHORIZED, "未认证", null);
    }

    public static <T> R<T> unauthorized(String msg) {
        return new R<>(UNAUTHORIZED, msg, null);
    }

    public static <T> R<T> forbidden() {
        return new R<>(FORBIDDEN, "无权限", null);
    }

    public static <T> R<T> forbidden(String msg) {
        return new R<>(FORBIDDEN, msg, null);
    }

    public static <T> R<T> notFound(String msg) {
        return new R<>(NOT_FOUND, msg, null);
    }

    public static <T> R<T> warn(String msg) {
        return new R<>(WARN, msg, null);
    }

    public static <T> R<T> warn(String msg, T data) {
        return new R<>(WARN, msg, data);
    }

    public R<T> code(int code) {
        this.code = code;
        return this;
    }

    public R<T> msg(String msg) {
        this.msg = msg;
        return this;
    }

    public boolean isSuccess() {
        return this.code == SUCCESS;
    }
}
