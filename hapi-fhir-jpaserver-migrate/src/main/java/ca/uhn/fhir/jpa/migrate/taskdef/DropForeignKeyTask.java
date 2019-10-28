package ca.uhn.fhir.jpa.migrate.taskdef;

/*-
 * #%L
 * HAPI FHIR JPA Server - Migration
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.jpa.migrate.DriverTypeEnum;
import ca.uhn.fhir.jpa.migrate.JdbcUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class DropForeignKeyTask extends BaseTableTask<DropForeignKeyTask> {

	private static final Logger ourLog = LoggerFactory.getLogger(DropForeignKeyTask.class);
	private String myConstraintName;
	private String myParentTableName;

	public DropForeignKeyTask(String theVersion) {
		super(theVersion);
	}

	public void setConstraintName(String theConstraintName) {
		myConstraintName = theConstraintName;
	}

	public void setParentTableName(String theParentTableName) {
		myParentTableName = theParentTableName;
	}

	@Override
	public void validate() {
		super.validate();

		Validate.isTrue(isNotBlank(myConstraintName));
		Validate.isTrue(isNotBlank(myParentTableName));
	}

	@Override
	public void execute() throws SQLException {

		Set<String> existing = JdbcUtils.getForeignKeys(getConnectionProperties(), myParentTableName, getTableName());
		if (!existing.contains(myConstraintName)) {
			ourLog.info("Don't have constraint named {} - No action performed", myConstraintName);
			return;
		}

		List<String> sqls = generateSql(getTableName(), myConstraintName, getDriverType());

		for (String next : sqls) {
			executeSql(getTableName(), next);
		}

	}

	@Nonnull
	static List<String> generateSql(String theTableName, String theConstraintName, DriverTypeEnum theDriverType) {
		List<String> sqls = new ArrayList<>();
		switch (theDriverType) {
			case MYSQL_5_7:
				// Lousy MYQL....
				sqls.add("alter table " + theTableName + " drop constraint " + theConstraintName);
				sqls.add("alter table " + theTableName + " drop index " + theConstraintName);
				break;
			case MARIADB_10_1:
			case POSTGRES_9_4:
			case DERBY_EMBEDDED:
			case H2_EMBEDDED:
			case ORACLE_12C:
			case MSSQL_2012:
				sqls.add("alter table " + theTableName + " drop constraint " + theConstraintName);
				break;
			default:
				throw new IllegalStateException();
		}
		return sqls;
	}

}
