package tech.qiantong.qdata.module.da.service.asset;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.Purpose;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.ReadMode;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedObjectProjectAuthorizerTest {
    private static final long USER = 927L, PROJECT = 144L, ASSET = 101L;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DataResourceAccess resources = mock(DataResourceAccess.class);
    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final ManagedObjectProjectAuthorizer authorizer =
            new ManagedObjectProjectAuthorizer(jdbc, resources, permissions);
    private final AtomicReference<List<Map<String, Object>>> assetRows =
            new AtomicReference<>(List.of(Map.of("type", "6")));
    private final AtomicReference<List<Long>> ownerProjects =
            new AtomicReference<>(List.of(PROJECT));
    private final Set<String> grants = new HashSet<>();

    @BeforeEach void setUp() {
        when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_USER_SQL,
                Long.class, USER)).thenReturn(1L);
        when(jdbc.queryForList(ManagedObjectProjectAuthorizer.ASSET_SQL, ASSET))
                .thenAnswer(call -> assetRows.get());
        when(jdbc.queryForList(ManagedObjectProjectAuthorizer.PROJECTS_SQL, Long.class, ASSET))
                .thenAnswer(call -> ownerProjects.get());
        when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_PROJECT_SQL,
                Long.class, PROJECT, 0, USER, USER)).thenReturn(1L);
        when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_PROJECT_SQL,
                Long.class, PROJECT, 1, USER, USER)).thenReturn(1L);
        when(resources.visibleProjectIds()).thenReturn(Set.of(PROJECT));
        when(resources.visibleAssetIds()).thenReturn(Set.of(ASSET));
        when(permissions.has(eq(PROJECT), anyString()))
                .thenAnswer(call -> grants.contains(call.getArgument(1)));
        grants.addAll(Set.of("da:assetFiles:add", "da:assetFiles:query",
                "da:assetFiles:export", "da:asset:query", "da:asset:export"));
    }

    @Test void singleProjectFileUsesDeclaredAddQueryAndExportPermissions() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            assertTrue(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertEquals(PROJECT, authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
            assertTrue(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
            assertTrue(authorizer.canReadOwn(USER, PROJECT,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            assertTrue(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            verify(permissions, atLeastOnce()).has(PROJECT, "da:assetFiles:add");
            verify(permissions, atLeastOnce()).has(PROJECT, "da:asset:query");
            verify(permissions, atLeastOnce()).has(PROJECT, "da:asset:export");
            verify(permissions, atLeastOnce()).has(PROJECT, "da:assetFiles:query");
            verify(permissions, atLeastOnce()).has(PROJECT, "da:assetFiles:export");
        }
    }

    @Test void childAddMatchesExistingChildSaveSemanticsWithoutParentEdit() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            assertFalse(grants.contains("da:asset:edit"));
            assertTrue(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertTrue(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        }
    }

    @Test void parentExportCannotReplacePurposeSpecificExportForEitherReadPath() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            grants.remove("da:assetFiles:export");
            assertTrue(authorizer.canReadOwn(USER, PROJECT,
                    Purpose.ASSET_FILE, ReadMode.PREVIEW));
            assertFalse(authorizer.canReadOwn(USER, PROJECT,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            grants.remove("da:assetFiles:query");
            assertFalse(authorizer.canReadOwn(USER, PROJECT,
                    Purpose.ASSET_FILE, ReadMode.PREVIEW));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.PREVIEW));
        }
    }

    @Test void currentUserMembershipAndMenuRevocationFailClosed() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            assertFalse(authorizer.canUpload(USER + 1, PROJECT, Purpose.ASSET_FILE));
            assertFalse(authorizer.canBind(USER + 1, PROJECT, ASSET, Purpose.ASSET_FILE));
            verifyNoInteractions(jdbc);

            when(resources.visibleProjectIds()).thenReturn(Set.of());
            assertFalse(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            when(resources.visibleProjectIds()).thenReturn(Set.of(PROJECT));
            assertFalse(authorizer.canBind(USER, PROJECT + 1, ASSET, Purpose.ASSET_FILE));
            assertFalse(authorizer.canReadAsset(USER, PROJECT + 1, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            grants.remove("da:assetFiles:add");
            assertFalse(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertFalse(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));

            grants.add("da:assetFiles:add");
            grants.remove("da:asset:export");
            assertTrue(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.PREVIEW));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            grants.remove("da:asset:query");
            assertFalse(authorizer.canReadOwn(USER, PROJECT,
                    Purpose.ASSET_FILE, ReadMode.PREVIEW));
        }
    }

    @Test void disabledAccountWithStillValidTokenCannotUploadOrReadOriginal() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            assertTrue(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertTrue(authorizer.canReadOwn(USER, PROJECT, Purpose.ASSET_FILE,
                    ReadMode.ORIGINAL));
            when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_USER_SQL,
                    Long.class, USER)).thenReturn(0L);
            assertFalse(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertFalse(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
            assertFalse(authorizer.canReadOwn(USER, PROJECT, Purpose.ASSET_FILE,
                    ReadMode.ORIGINAL));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
        }
    }

    @Test void staleLoginAdminVisibilityCannotReplaceCurrentDbProjectMembership() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            // DataResourceAccess can still expose the project through a cached LoginUser admin role.
            when(resources.visibleProjectIds()).thenReturn(Set.of(PROJECT));
            when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_PROJECT_SQL,
                    Long.class, PROJECT, 0, USER, USER)).thenReturn(0L);
            assertFalse(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            assertFalse(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
            assertFalse(authorizer.canReadOwn(USER, PROJECT, Purpose.ASSET_FILE, ReadMode.ORIGINAL));
        }
    }

    @Test void activeDbAdministratorStillNeedsAnActiveProject() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            when(permissions.isAdministrator()).thenReturn(true);
            assertTrue(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
            when(jdbc.queryForObject(ManagedObjectProjectAuthorizer.CURRENT_PROJECT_SQL,
                    Long.class, PROJECT, 1, USER, USER)).thenReturn(0L);
            assertFalse(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_FILE));
        }
    }

    @Test void rejectsDatasetArchivedWrongTypeNoProjectAndMultipleProjects() {
        assertEquals(PROJECT, authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
        assertNull(authorizer.projectOfAsset(ASSET, Purpose.ASSET_GEO));
        assetRows.set(List.of(Map.of("type", "6", "dataset_id", 18L)));
        assertNull(authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
        assetRows.set(List.of()); // archived/inactive asset is excluded by the SQL predicate
        assertNull(authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
        assetRows.set(List.of(Map.of("type", "6")));
        ownerProjects.set(List.of());
        assertNull(authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
        ownerProjects.set(List.of(PROJECT, 145L));
        assertNull(authorizer.projectOfAsset(ASSET, Purpose.ASSET_FILE));
    }

    @Test void typeChangeOrLostAssetVisibilityBlocksBoundReadAndBind() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            assertTrue(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            assetRows.set(List.of(Map.of("type", "4")));
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_FILE, ReadMode.ORIGINAL));
            assertFalse(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
            grants.add("da:assetGeo:add");
            grants.add("da:assetGeo:query");
            grants.add("da:assetGeo:export");
            assertTrue(authorizer.canUpload(USER, PROJECT, Purpose.ASSET_GEO));
            assertTrue(authorizer.canBind(USER, PROJECT, ASSET, Purpose.ASSET_GEO));
            assertTrue(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_GEO, ReadMode.ORIGINAL));
            when(resources.visibleAssetIds()).thenReturn(Set.of());
            assertFalse(authorizer.canReadAsset(USER, PROJECT, ASSET,
                    Purpose.ASSET_GEO, ReadMode.ORIGINAL));
        }
    }

    @Test void missingFeatureFlagNeverRegistersThisBean() {
        var condition = ManagedObjectProjectAuthorizer.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("qdata.managed-object", condition.prefix());
        assertArrayEquals(new String[]{"enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Test void featureFlagActuallyControlsSpringBeanRegistration() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(ManagedObjectProjectAuthorizer.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(DataResourceAccess.class, () -> resources)
                .withBean(ProjectPermissionService.class, () -> permissions);
        context.run(beans -> assertEquals(0, beans.getBeansOfType(ManagedObjectProjectAuthorizer.class).size()));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertEquals(1, beans.getBeansOfType(ManagedObjectProjectAuthorizer.class).size()));
    }
}
