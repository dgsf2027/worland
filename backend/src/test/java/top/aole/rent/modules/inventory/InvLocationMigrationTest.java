package top.aole.rent.modules.inventory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class InvLocationMigrationTest {
    @Test
    void migrationPreservesExistingAssetsAndStoresAndClearsCoordinates() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:invlocation;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE yc_rent_inv_item(id bigint primary key, location varchar(128), stock_qty int)");
        jdbc.update("INSERT INTO yc_rent_inv_item VALUES(1, '仓库二楼', 9)");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V126__inventory_map_location.sql"));
        migration.setSqlScriptEncoding("UTF-8");
        migration.execute(ds);
        assertNull(jdbc.queryForObject("SELECT map_longitude FROM yc_rent_inv_item WHERE id=1", BigDecimal.class));
        jdbc.update("UPDATE yc_rent_inv_item SET map_longitude=?, map_latitude=?, map_provider='browser', map_source='gps', map_accuracy=12.5, map_updated_at=CURRENT_TIMESTAMP, map_updated_by='测试' WHERE id=1",
                new BigDecimal("113.2644001"), new BigDecimal("23.1291000"));
        assertEquals(new BigDecimal("113.2644001"), jdbc.queryForObject("SELECT map_longitude FROM yc_rent_inv_item WHERE id=1", BigDecimal.class));
        jdbc.update("UPDATE yc_rent_inv_item SET map_longitude=NULL, map_latitude=NULL, map_address=NULL, map_accuracy=NULL WHERE id=1");
        assertNull(jdbc.queryForObject("SELECT map_longitude FROM yc_rent_inv_item WHERE id=1", BigDecimal.class));
        assertEquals("仓库二楼", jdbc.queryForObject("SELECT location FROM yc_rent_inv_item WHERE id=1", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT stock_qty FROM yc_rent_inv_item WHERE id=1", Integer.class));
    }
}
