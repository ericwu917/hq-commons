# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码库工作时提供指引。

## 项目性质

`io.hqwu.commons` —— taige 自维护的 Java 基础组件库（多模块 Maven），被多个业务后端仓当私有依赖：连接池 HQCP、MyBatis-Plus 扩展、Spring MVC 增强、邮件 starter、工具类。**它是被依赖方**。

- 基础包 `io.hqwu.commons`；**Java 17 字节码目标**（下游有 17 也有 25，库不抬高门槛）；父 pom 导入 Spring Boot 3 BOM。
- 当前版本线 `1.17.0-SNAPSHOT`，**minor 号跟 JDK 大版本走**（1.17.x = JDK 17 线；1.4.x 是 JDK 8 时代）。版本号由 maven-release-plugin 在发布时改，**不手动改 `<version>`**。

## 下游消费方

下游是多个业务仓，**Spring Boot 3.x 与 4.x 消费方并存、Java 17 与 25 并存**，其中 SB3 消费方在生产运行。本仓不登记具体是哪些仓（各业务仓自己的文档记它依赖了什么）；改公开 API、自动装配、默认行为前，按「两条线都不能断」判。

## 模块依赖图

```
hq-utils                      基础工具，零 Spring 依赖，含 SPI 扩展点（如 SecurityService）
├── hq-utils-spring           hq-utils 的 Spring 适配（SPI → Spring bean，自动感知远程 KeyManageService）
├── hq-spring-webmvc          Spring MVC 增强：BeanConverter（@ValueOf + cglib BeanCopier）、校验器、过滤器、Redis 脚本
├── hq-cp                     连接池 HQCP 本体，与 Spring 解耦、可独立用；多方言（MySQL / Oracle / DB2 / OceanBase）+ SQL 敏感信息脱敏
│   ├── hq-cp-boot-starter    HQCP 的 Spring Boot 3 自动装配
│   └── hq-cp-boot4-starter   同上的 Spring Boot 4 变体：自带 SB 4.x BOM；类布局与 SB3 版完全相同（同包同名，两者不会同时上 classpath）
├── hq-mybatis-plus-extension MainLambdaQueryWrapper / JoinQueryWrapper 联表封装、CommonFieldsFiller、代码生成器；也依赖 hq-spring-webmvc
├── hq-email-boot-starter     Jakarta Mail 邮件服务 Spring Boot 3 starter（SB4 下也能装配，见下）
└── hq-dubbo-ext              已废弃（不在 modules 里）
report-aggregate              JaCoCo 聚合报告模块，不是库
hq-boot4-compat-test          SB4 消费方视角的兼容回归（只有测试：不发布、不计覆盖率）
```

自动装配一律用 Spring Boot 3 风格 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（不用 `spring.factories`）。带自动装配的只有 `hq-cp-boot-starter` / `hq-cp-boot4-starter` / `hq-email-boot-starter` 三个。

## 构建与测试

```bash
mvn clean install -DskipTests            # 全量装到 ~/.m2（下游本地联调靠这个）
mvn test                                 # 全部单测（*IntTest 被 surefire 排除）
mvn test -pl hq-utils                    # 单模块
mvn test -pl hq-spring-webmvc -Dtest=BeanConverterTest
mvn test -pl hq-spring-webmvc -Dtest=BeanConverterTest#testConvert
mvn verify                               # 含 JaCoCo 覆盖率门（见下）
```

无 Maven wrapper，用系统 Maven，需要 JDK 17。

**测试栈**：JUnit 5 + Mockito 为主，hq-cp 里有 EasyMock，邮件用 Greenmail。集成测试类名以 `IntTest` 结尾、默认不跑。日志级别由 `test.logger.level` 控制（默认 WARN）。

**JaCoCo 门（`mvn verify` 强制，不许静默调低）**：Bundle 指令 90% / 分支 85%；Package 行 85%；Class 行 80%、漏测复杂度路径 ≤ 20。排除 `**/entity/**`、`**/dto/**`、`**/enums/**`、`**/config/**`、`**/*Exception.class`、`**/*Application.class`。模块级额外排除只走 `<excludes combine.children="append">`（现有：hq-cp `OracleUtil`、hq-mybatis-plus-extension `CodeGenerator`），不改父 pom。

## 分支与发布

- **remote**：`ericwu917@github` = `ericwu917/hq-commons` —— **真源**（2026-09-12 起），Actions 与发布都在这；下游 `payment26-backend` 也在这个账号下。`taige@github` = `taige/hq-commons`：旧主仓，**账号被 GitHub 以 billing 问题锁定、Actions 一步都跑不了**（`The job was not started because your account is locked due to a billing issue`，与用量无关：public 仓分钟数免费，且锁定前 5 个月零运行），原样留着不动，别往它推。`ali_codeup`：云效 Codeup 镜像，落后。
- `gh` 与 `git push` 都用全局 active 账号 `ericwu917`，**本仓不需要切账号**（历史上为 taige 配的仓库级 credential helper 已删除）。
- **没有 `origin`**：全局 worktree hook 找不到 `origin/develop` 会退到当前 HEAD，开 worktree 前先 `git fetch ericwu917@github && git checkout develop && git pull`。本仓已设 `gh repo set-default ericwu917/hq-commons`、`remote.pushDefault` / `checkout.defaultRemote` = `ericwu917@github`。
- **版本现状（2026-09-12）**：`develop` 是当前线（1.17.0-SNAPSHOT）。**最后一次 release 是 v1.4.0（2025-03-06）**，`master` 停在 `1.4.1-SNAPSHOT`；**1.5–1.16 不存在**——5c67391（2026-01-14）随 JDK 17 升级把版本号从 `1.4.1-SNAPSHOT` 直接改成 `1.17.0-SNAPSHOT`，之后只发过 SNAPSHOT，下游全在吃它。下次 release 就是 1.17.0，develop → master 会是跨 1.4 → 1.17 的大合并。
- **发布全靠 GitHub Actions，不在本地跑 release**：`feature/*` → `develop`（推送触发 `maven-snapshot.yml`：SNAPSHOT 发 GitHub Packages + Aliyun 云效）→ 把 develop 合到 `master` 推上去（触发 `maven-release.yml`：job 1 `release:prepare/perform` 打 tag `v<version>`、bump 下一个 SNAPSHOT、发 GitHub Packages；job 2 调 `maven-release-aliyun.yml` 按 tag 发 Aliyun）→ 手动 dispatch `maven-release-merge-to-develop.yml` 把 master ff-merge 回 develop。**Aliyun 那步失败只重跑它**：Re-run failed jobs，或手动 dispatch `maven-release-aliyun.yml` 填同一个 tag；别重跑整个 release，那会再切一个版本。
- 发布产物两处：GitHub Packages（`maven.pkg.github.com/ericwu917/hq-commons`，pom 的 distributionManagement，含 sources jar）与 Aliyun 云效 packages（workflow 里用 `-Dalt*DeploymentRepository` 指过去）。业务仓从 Aliyun 拉。**Aliyun 有意不发 sources**：两条 Aliyun 步骤都是 `package deploy:deploy` 而非完整 `deploy`，就是为了跳过 verify 阶段的 source plugin，别"修"成 `deploy`。
- **SNAPSHOT 不是终点**：下游要打 release tag 之前，本仓得先发 release 版本。
- **CI 上 `hq-cp` 的两个测试是已知 flaky**，重跑即过，别当成真失败：`HqcpTest#testGetConnectionLIFO`（拿到未初始化的 EasyMock 对象）与 `HqcpTest#testCheckoutTimeoutWaitForever`（`No suitable driver found for jdbc:mock`）。taige 仓历史上的 snapshot run 就是"失败→重跑→成功"的规律。

## 编码约定

- 注释、Javadoc 中文；commit message **简短英文**、聚焦主代码变更（只改测试时才描述测试）。
- 全模块用 Lombok。
- hq-cp 与 hq-utils **不引 Spring Boot**（hq-cp 只依赖 `spring-core`）；Boot 相关只进 `*-boot-starter`。
- 公开 API 变更走**新增不删改**：要改签名先加新方法、旧的 `@Deprecated` 保留一个 minor 版本。
- 覆盖率门是设计约束的一部分：新代码带测试到位再提交，别靠 excludes 绕。

## 多版本 Spring Boot 并存的规矩

下游同时有 SB3 与 SB4 消费方。Spring Boot 4 把 `spring-boot-autoconfigure` 按技术拆成独立模块（如 `DataSourceProperties` 迁到 `org.springframework.boot.jdbc.autoconfigure`），旧包路径在 SB4 下 `ClassNotFound`。因此：

- 既有 `*-boot-starter` 是 **SB3 线**，不改成 SB4-only；SB4 支持用**新模块**承接（`hq-cp-boot4-starter`）：新模块自己 import `spring-boot-dependencies` 4.x BOM，子模块自己的 import 先于从父 pom 继承的 3.x BOM（已用 `dependency:tree` 验证），父 pom 的 BOM 版本不动。
- 库的字节码目标保持 Java 17，两条线共用 hq-cp / hq-utils 等无 Boot 依赖的本体。
- **SB4 消费方的接法**（`hq-boot4-compat-test` 在 SB 4.1.1 / MP boot4 starter 3.5.17 / HV 9 / Jackson 3 下验证过）：`hq-cp-boot4-starter` 替换 `hq-cp-boot-starter`；`hq-utils-spring` / `hq-spring-webmvc` / `hq-mybatis-plus-extension` / `hq-email-boot-starter` 原样用，不做 boot4 变体。
- **SB4 已知限制**：`JsonFieldArgumentProcessor` 内部用 Jackson 2 的 `JsonNode`，把 SB4 默认的 Jackson 3 转换器喂给它会 400（`HttpMessageNotReadableException`）——必须显式 `new JsonFieldArgumentProcessor(List.of(new MappingJackson2HttpMessageConverter()))`（SB3 消费方本来就是这么接的）；`ValidatedDeserializer` 同理只对 Jackson 2 `ObjectMapper` 生效。MP 3.5.9+ 的 `PaginationInnerInterceptor` 要另加 `mybatis-plus-jsqlparser`。
- 改共享模块后 `mvn verify` 会顺带跑 `hq-boot4-compat-test`；单跑它（`-pl`）前先 `install -DskipTests` 上游，它用的是已构建 jar，否则拿到的是 `~/.m2` 里的旧包。

## 反模式禁忌

- ❌ 手改 `<version>`（release plugin 管）；❌ 静默调低 JaCoCo 阈值或加 excludes 绕门
- ❌ 在 hq-cp / hq-utils 引入 Spring Boot 依赖
- ❌ 改既有 `*-boot-starter` 去迁就 SB4（会断 SB3 消费方）——新模块承接
- ❌ 碰 `hq-dubbo-ext`（已废弃）
- ❌ 直接手编 `AGENTS.md`（由 `~/bin/sync-agents-md` 从本文件派生；改完本文件在仓根跑一次）
