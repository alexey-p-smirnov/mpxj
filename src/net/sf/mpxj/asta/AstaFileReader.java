/*
 * file:       AstaFileReader.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2012
 * date:       23/04/2012
 */

/*
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation; either version 2.1 of the License, or (at your
 * option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */

package net.sf.mpxj.asta;

import java.io.InputStream;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

import net.sf.mpxj.DayType;
import net.sf.mpxj.MPXJException;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.Task;
import net.sf.mpxj.listener.ProjectListener;
import net.sf.mpxj.reader.AbstractProjectReader;
import net.sf.mpxj.utility.InputStreamTokenizer;
import net.sf.mpxj.utility.Tokenizer;

/**
 * This class provides a generic front end to read project data from
 * a database.
 */
public final class AstaFileReader extends AbstractProjectReader
{
   /**
    * {@inheritDoc}
    */
   @Override public void addProjectListener(ProjectListener listener)
   {
      if (m_projectListeners == null)
      {
         m_projectListeners = new LinkedList<ProjectListener>();
      }
      m_projectListeners.add(listener);
   }

   /**
    * {@inheritDoc}
    */
   @Override public ProjectFile read(InputStream inputStream) throws MPXJException
   {
      try
      {
         m_reader = new AstaReader();
         ProjectFile project = m_reader.getProject();
         project.addProjectListeners(m_projectListeners);

         m_tables = new HashMap<String, List<Row>>();

         processFile(inputStream);

         processProjectHeader();
         processCalendars();
         processResources();
         processTasks();
         processPredecessors();
         processAssignments();

         updateStructure(project);

         return (project);
      }

      catch (SQLException ex)
      {
         throw new MPXJException(MPXJException.READ_ERROR, ex);
      }

      finally
      {
         m_reader = null;
      }
   }

   /**
    * Tokenizes the input file and extracts the required data.
    * 
    * @param is input stream
    * @throws MPXJException
    */
   private void processFile(InputStream is) throws MPXJException
   {
      try
      {
         Tokenizer tk = new InputStreamTokenizer(is);
         tk.setDelimiter(DELIMITER);
         ArrayList<String> columns = new ArrayList<String>();
         String nextTokenPrefix = null;

         while (tk.getType() != Tokenizer.TT_EOF)
         {
            columns.clear();
            TableDefinition table = null;

            while (tk.nextToken() == Tokenizer.TT_WORD)
            {
               String token = tk.getToken();
               if (columns.size() == 0)
               {
                  if (token.charAt(0) == '#')
                  {
                     int index = token.lastIndexOf(':');
                     if (index != -1)
                     {
                        String headerToken;
                        if (token.endsWith("-") || token.endsWith("="))
                        {
                           headerToken = token;
                           token = null;
                        }
                        else
                        {
                           headerToken = token.substring(0, index);
                           token = token.substring(index + 1);
                        }

                        RowHeader header = new RowHeader(headerToken);
                        table = TABLE_DEFINITIONS.get(header.getType());
                        columns.add(header.getID());
                     }
                  }
               }

               if (table != null && token != null)
               {
                  if (token.startsWith("<\"") && !token.endsWith("\">"))
                  {
                     nextTokenPrefix = token;
                  }
                  else
                  {
                     if (nextTokenPrefix != null)
                     {
                        token = nextTokenPrefix + DELIMITER + token;
                        nextTokenPrefix = null;
                     }

                     columns.add(token);
                  }
               }
            }

            if (table != null && columns.size() > 1)
            {
               FileRow row = new FileRow(table, columns);
               List<Row> rows = m_tables.get(table.getName());
               if (rows == null)
               {
                  rows = new LinkedList<Row>();
                  m_tables.put(table.getName(), rows);
               }
               rows.add(row);
            }
         }
      }

      catch (Exception ex)
      {
         throw new MPXJException(MPXJException.READ_ERROR, ex);
      }
   }

   /**
    * Select the project header row from the database.
    * 
    * @throws SQLException
    */
   private void processProjectHeader() throws SQLException
   {
      List<Row> rows = getTable("PROJECT_SUMMARY");
      if (rows.isEmpty() == false)
      {
         m_reader.processProjectHeader(rows.get(0));
      }
   }

   /**
    * Extract calendar data from the file.
    * 
    * @throws SQLException
    */
   private void processCalendars() throws SQLException
   {
      List<Row> rows = getTable("EXCEPTIONN");
      Map<Integer, DayType> exceptionMap = m_reader.createExceptionTypeMap(rows);

      rows = getTable("WORK_PATTERN");
      Map<Integer, Row> workPatternMap = m_reader.createWorkPatternMap(rows);

      rows = new LinkedList<Row>();// getTable("WORK_PATTERN_ASSIGNMENT"); // Need to generate an example
      Map<Integer, List<Row>> workPatternAssignmentMap = m_reader.createWorkPatternAssignmentMap(rows);

      rows = getTable("EXCEPTION_ASSIGNMENT");
      Map<Integer, List<Row>> exceptionAssignmentMap = m_reader.createExceptionAssignmentMap(rows);

      rows = getTable("TIME_ENTRY");
      Map<Integer, List<Row>> timeEntryMap = m_reader.createTimeEntryMap(rows);

      rows = getTable("CALENDAR");
      Collections.sort(rows, CALENDAR_COMPARATOR);
      for (Row row : rows)
      {
         m_reader.processCalendar(row, workPatternMap, workPatternAssignmentMap, exceptionAssignmentMap, timeEntryMap, exceptionMap);
      }

      //
      // Update unique counters at this point as we will be generating
      // resource calendars, and will need to auto generate IDs
      //
      m_reader.getProject().updateUniqueCounters();
   }

   /**
    * Process resources.
    * 
    * @throws SQLException
    */
   private void processResources() throws SQLException
   {
      List<Row> permanentRows = getTable("PERMANENT_RESOURCE");
      List<Row> consumableRows = getTable("CONSUMABLE_RESOURCE");

      Collections.sort(permanentRows, PERMANENT_RESOURCE_COMPARATOR);
      Collections.sort(consumableRows, CONSUMABLE_RESOURCE_COMPARATOR);

      m_reader.processResources(permanentRows, consumableRows);
   }

   /**
    * Process tasks.
    * 
    * @throws SQLException
    */
   private void processTasks() throws SQLException
   {
      List<Row> barRows = getTable("BAR");
      List<Row> expandedTaskRows = getTable("EXPANDED_TASK");
      List<Row> bars = join(barRows, "EXPANDED_TASK", "EXPANDED_TASK", expandedTaskRows, "EXPANDED_TASKID");
      filterNotNull(bars, "STARV");
      Collections.sort(bars, BAR_COMPARATOR);

      List<Row> tasks = getTable("TASK");
      Collections.sort(tasks, TASK_COMPARATOR);

      List<Row> milestones = getTable("MILESTONE");

      m_reader.processTasks(bars, tasks, milestones);
   }

   /**
    * Process predecessors.
    * 
    * @throws SQLException
    */
   private void processPredecessors() throws SQLException
   {

      List<Row> rows = getTable("LINK");
      Collections.sort(rows, LINK_COMPARATOR);
      m_reader.processPredecessors(rows);
   }

   /**
    * Process resource assignments.
    * 
    * @throws SQLException
    */
   private void processAssignments() throws SQLException
   {
      List<Row> allocationRows = getTable("PERMANENT_SCHEDUL_ALLOCATION");
      List<Row> skillRows = getTable("PERM_RESOURCE_SKILL");
      List<Row> permanentAssignments = join(allocationRows, "ALLOCATIOP_OF", "PERM_RESOURCE_SKILL", skillRows, "PERM_RESOURCE_SKILLID");
      Collections.sort(permanentAssignments, ALLOCATION_COMPARATOR);
      m_reader.processAssignments(permanentAssignments);
   }

   /**
    * Cleans up the structure, removes unnecessary summary tasks and
    * ensures tasks with blank names inherit their names from the
    * parent task.
    * 
    * @param project ProjectFile instance
    */
   private void updateStructure(ProjectFile project)
   {
      //
      // Build the hierarchy
      //
      project.updateStructure();

      //
      // Ensure tasks with blank names inherit parent task names
      //
      for (Task task : project.getChildTasks())
      {
         updateBlankNames(null, task);
      }

      //
      // Create a list of tasks to prune
      //
      List<Task> tasks = new LinkedList<Task>();
      for (Task task : project.getAllTasks())
      {
         if (task.getChildTasks().size() == 1 && task.getChildTasks().get(0).getChildTasks().size() == 0 && task.getWBS().equals("-"))
         {
            tasks.add(task);
         }
      }

      //
      // Prune these tasks
      //
      for (Task task : tasks)
      {
         Task child = task.getChildTasks().get(0);
         Task parent = task.getParentTask();

         if (parent == null)
         {
            List<Task> parentList = project.getChildTasks();
            int parentListIndex = parentList.indexOf(task);
            if (parentListIndex == -1)
            {
               parentList.add(child);
            }
            else
            {
               parentList.add(parentListIndex, child);
            }
         }
         else
         {
            parent.addChildTaskBefore(child, task);
         }
         task.getChildTasks().clear();
         task.remove();
      }

      //
      // Ensure we have no gaps in the ID sequence
      //
      project.renumberTaskIDs();

      project.updateStructure();
   }

   /**
    * Called recursively to replace blank task names 
    * with names inherited from parent tasks.
    * 
    * @param parent parent task
    * @param task current task
    */
   private void updateBlankNames(Task parent, Task task)
   {
      if (parent != null && (task.getName() == null || task.getName().length() == 0))
      {
         task.setName(parent.getName());
      }

      for (Task child : task.getChildTasks())
      {
         updateBlankNames(task, child);
      }
   }

   /**
    * Very basic implementation of an inner join between two result sets.
    * 
    * @param leftRows left result set
    * @param leftColumn left foreign key column
    * @param rightTable right table name
    * @param rightRows right result set
    * @param rightColumn right primary key column
    * @return joined result set
    */
   private List<Row> join(List<Row> leftRows, String leftColumn, String rightTable, List<Row> rightRows, String rightColumn)
   {
      List<Row> result = new LinkedList<Row>();

      RowComparator leftComparator = new RowComparator(new String[]
      {
         leftColumn
      });
      RowComparator rightComparator = new RowComparator(new String[]
      {
         rightColumn
      });
      Collections.sort(leftRows, leftComparator);
      Collections.sort(rightRows, rightComparator);

      ListIterator<Row> rightIterator = rightRows.listIterator();
      Row rightRow = rightIterator.hasNext() ? rightIterator.next() : null;

      for (Row leftRow : leftRows)
      {
         Integer leftValue = leftRow.getInteger(leftColumn);
         boolean match = false;

         while (rightRow != null)
         {
            Integer rightValue = rightRow.getInteger(rightColumn);
            int comparison = leftValue.compareTo(rightValue);
            if (comparison == 0)
            {
               match = true;
               break;
            }

            if (comparison < 0)
            {
               if (rightIterator.hasPrevious())
               {
                  rightRow = rightIterator.previous();
               }
               break;
            }

            rightRow = rightIterator.next();
         }

         if (match && rightRow != null)
         {
            Map<String, Object> newMap = new HashMap<String, Object>(((MapRow) leftRow).getMap());

            for (Entry<String, Object> entry : ((MapRow) rightRow).getMap().entrySet())
            {
               String key = entry.getKey();
               if (newMap.containsKey(key))
               {
                  key = rightTable + "." + key;
               }
               newMap.put(key, entry.getValue());
            }

            result.add(new MapRow(newMap));
         }
      }

      return result;
   }

   /**
    * Removes rows with a null value in a given column.
    * 
    * @param rows result set
    * @param column column being tested for null
    */
   private void filterNotNull(List<Row> rows, String column)
   {
      ListIterator<Row> iter = rows.listIterator();
      while (iter.hasNext())
      {
         MapRow row = (MapRow) iter.next();
         if (row.getObject(column) == null)
         {
            iter.remove();
         }
      }
   }

   /**
    * Retrieve table data, return an empty result set if no table data is present.
    * 
    * @param name table name
    * @return table data
    */
   private List<Row> getTable(String name)
   {
      List<Row> result = m_tables.get(name);
      if (result == null)
      {
         result = new LinkedList<Row>();
      }
      return result;
   }

   private AstaReader m_reader;
   private List<ProjectListener> m_projectListeners;
   private Map<String, List<Row>> m_tables;

   private static final ColumnDefinition[] PROJECT_SUMMARY_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("PROJECT_SUMMARYID", Types.INTEGER),
      new ColumnDefinition("DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("STARU", Types.TIMESTAMP),
      new ColumnDefinition("ENE", Types.TIMESTAMP),
      new ColumnDefinition("FISCAL_YEAR_START", Types.TIMESTAMP),
      new ColumnDefinition("LAST_ID_USED_IN_BASELINE", Types.INTEGER),
      new ColumnDefinition("DS_ID_BOOKED_FROM", Types.INTEGER),
      new ColumnDefinition("WBN_CONSTRAINT", Types.INTEGER),
      new ColumnDefinition("WBN_RANGE_FROM", Types.INTEGER),
      new ColumnDefinition("WBN_RANGE_TO", Types.INTEGER),
      new ColumnDefinition("WBN_INCREMENT", Types.INTEGER),
      new ColumnDefinition("WBN_MINIMUM_WIDTH", Types.INTEGER),
      new ColumnDefinition("SPARF_INTEGER", Types.INTEGER),
      new ColumnDefinition("UTID_CONSTRAINT", Types.INTEGER),
      new ColumnDefinition("UTID_START_VALUE", Types.INTEGER),
      new ColumnDefinition("UTID_INCREMENT", Types.INTEGER),
      new ColumnDefinition("UTID_SUB_INCREMENT", Types.INTEGER),
      new ColumnDefinition("UTID_MINIMUM_WIDTH", Types.INTEGER),
      new ColumnDefinition("INITIAL_VIEW", Types.INTEGER),
      new ColumnDefinition("POINT_RELEASE", Types.INTEGER),
      new ColumnDefinition("TIMESHEET_PROJECT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_ID_USED_IN_ARCHIVES", Types.INTEGER),
      new ColumnDefinition("PROJECT_VERSION", Types.INTEGER),
      new ColumnDefinition("STANDARD_WORK_MIN_FADE", Types.INTEGER),
      new ColumnDefinition("BOOKOUT_SET_UNIQUE_ID", Types.INTEGER),
      new ColumnDefinition("NUMBER_BOOKED_OUT_SETS", Types.INTEGER),
      new ColumnDefinition("SHORT_NAME", Types.VARCHAR),
      new ColumnDefinition("LONG_NAME", Types.VARCHAR),
      new ColumnDefinition("LOCAL_FILE_BOOKED_FROM", Types.VARCHAR),
      new ColumnDefinition("WBN_START_VALUE", Types.VARCHAR),
      new ColumnDefinition("WBN_PATHNAME_SEPARATOR", Types.VARCHAR),
      new ColumnDefinition("WBN_TASK_SEPARATOR", Types.VARCHAR),
      new ColumnDefinition("WBN_PREFIX", Types.VARCHAR),
      new ColumnDefinition("LAST_WBN_USED", Types.VARCHAR),
      new ColumnDefinition("PROJECT_FOR", Types.VARCHAR),
      new ColumnDefinition("PROJECT_BY", Types.VARCHAR),
      new ColumnDefinition("PATH_SEPARATOR", Types.VARCHAR),
      new ColumnDefinition("CHART_PATH_SEPARATOR", Types.VARCHAR),
      new ColumnDefinition("UTID_PREFIX", Types.VARCHAR),
      new ColumnDefinition("TIMESHEET_CONNECTION", Types.VARCHAR),
      new ColumnDefinition("WBS_PATH_SEPARATOR", Types.VARCHAR),
      new ColumnDefinition("PROJECT_GUID", Types.VARCHAR),
      new ColumnDefinition("DURATION_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("SECURITY_CODELIBRARY", Types.INTEGER),
      new ColumnDefinition("BOOKOUT_COUNTER", Types.INTEGER),
      new ColumnDefinition("PROGRESS_METHOD", Types.INTEGER),
      new ColumnDefinition("FORMULA_DATE_FORMAT", Types.INTEGER),
      new ColumnDefinition("WBN_ENABLED", Types.BIT),
      new ColumnDefinition("OLD_START_VALUE", Types.BIT),
      new ColumnDefinition("IGNORE_SATISFIED_COSTS", Types.BIT),
      new ColumnDefinition("UTID_ENABLE_SUB_INCREMENTS", Types.BIT),
      new ColumnDefinition("EXCLUSIVE_CUSTOM_TIME_UNITS", Types.BIT),
      new ColumnDefinition("IS_AN_ARCHIVE", Types.BIT),
      new ColumnDefinition("SORT_BY_SORT_ORDER", Types.BIT),
      new ColumnDefinition("USE_PROJECT_BASELINES_FOR_JP", Types.BIT),
      new ColumnDefinition("USE_ROLLED_UP_OPC_WEIGHTINGS", Types.BIT),
      new ColumnDefinition("DISPLAY_WBS_BY_CODE", Types.BIT),
      new ColumnDefinition("INHERIT_FROM_NEIGHBOUR", Types.BIT),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] BAR_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("BARID", Types.INTEGER),
      new ColumnDefinition("STARV", Types.TIMESTAMP),
      new ColumnDefinition("ENF", Types.TIMESTAMP),
      new ColumnDefinition("NATURAL_ORDER", Types.INTEGER),
      new ColumnDefinition("SPARI_INTEGER", Types.INTEGER),
      new ColumnDefinition("NAMH", Types.VARCHAR),
      new ColumnDefinition("EXPANDED_TASK", Types.INTEGER),
      new ColumnDefinition("PRIORITY", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLE", Types.BIT),
      new ColumnDefinition("MARK_FOR_HIDING", Types.BIT),
      new ColumnDefinition("TASKS_MAY_OVERLAP", Types.BIT),
      new ColumnDefinition("SUBPROJECT_ID", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   // Followed by user defined columns which differ by project
   };

   private static final ColumnDefinition[] CALENDAR_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("CALENDARID", Types.INTEGER),
      new ColumnDefinition("SPARL_INTEGER", Types.INTEGER),
      new ColumnDefinition("NAMK", Types.VARCHAR),
      new ColumnDefinition("DOMINANT_WORK_PATTERN", Types.INTEGER),
      new ColumnDefinition("CALENDAR", Types.INTEGER),
      new ColumnDefinition("DISPLAY_THRESHOLD", Types.TIMESTAMP),
      new ColumnDefinition("NO_WORKING_TIME_COLOUR", Types.INTEGER),
      new ColumnDefinition("WORKING_TIME_COLOUR", Types.INTEGER),
      new ColumnDefinition("NUMBERING", Types.INTEGER),
      new ColumnDefinition("SHOW_PAST_DATES", Types.BIT),
      new ColumnDefinition("ISO8601_WEEK_NUMBERING", Types.BIT),
      new ColumnDefinition("CREATED_AS_FOLDER", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] EXCEPTIONN_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("EXCEPTIONNID", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDARROW_TYPE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDLENGTH", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDEDGE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDBORDET_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDINSIDG_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STAPPANDPLACEMENW", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDBLIP_TYPE", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDSCALEY", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDSCALEZ", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDGAP", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDBORDES_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDINSIDF_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STAPPANDPLACEMENV", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STAPPANDSCALEX", Types.DOUBLE),
      new ColumnDefinition("LIN_STOUT_STAPPANDWIDTH", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STAPPANDBORDER_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STAPPANDINSIDE_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STAPPANDLINE_TYPE", Types.INTEGER),
      new ColumnDefinition("APPANDFOREGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("APPANDBACKGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("APPANDPATTERN", Types.INTEGER),
      new ColumnDefinition("UNIQUE_BIT_FIELD", Types.INTEGER),
      new ColumnDefinition("NAML", Types.VARCHAR),
      new ColumnDefinition("TYPG", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] EXCEPTION_ASSIGNMENT_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("EXCEPTION_ASSIGNMENTID", Types.INTEGER),
      //new ColumnDefinition("ORDF", Types.INTEGER),
      new ColumnDefinition("STARU_DATE", Types.TIMESTAMP),
      new ColumnDefinition("ENE_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EXCEPTIOO", Types.INTEGER)
   };

   private static final ColumnDefinition[] TIME_ENTRY_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("TIME_ENTRYID", Types.INTEGER),
      new ColumnDefinition("EXCEPTIOP", Types.INTEGER),
      new ColumnDefinition("START_TIME", Types.TIMESTAMP),
      new ColumnDefinition("END_TIME", Types.TIMESTAMP)
   };

   private static final ColumnDefinition[] WORK_PATTERN_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("WORK_PATTERNID", Types.INTEGER),
      new ColumnDefinition("DEFAULT_OFFSET", Types.INTEGER),
      new ColumnDefinition("NAMN", Types.VARCHAR),
      new ColumnDefinition("DEFAULT_ALIGNMENT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("CREATED_AS_FOLDER", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] TASK_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("TASKID", Types.INTEGER),
      new ColumnDefinition("GIVEN_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("GIVEN_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("GIVEN_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("RESUME", Types.TIMESTAMP),
      new ColumnDefinition("GIVEN_START", Types.TIMESTAMP),
      new ColumnDefinition("LATEST_PROGRESS_PERIOD", Types.INTEGER),
      new ColumnDefinition("TASK_WORK_RATE_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("TASK_WORK_RATE", Types.DOUBLE),
      new ColumnDefinition("PLACEMENT", Types.INTEGER),
      new ColumnDefinition("BEEN_SPLIT", Types.BIT),
      new ColumnDefinition("INTERRUPTIBLE", Types.BIT),
      new ColumnDefinition("HOLDING_PIN", Types.BIT),
      new ColumnDefinition("ACTUAL_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("EARLY_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LATE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("FREE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("START_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("END_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EFFORT_BUDGET", Types.DOUBLE),
      new ColumnDefinition("NATURAO_ORDER", Types.INTEGER),
      new ColumnDefinition("LOGICAL_PRECEDENCE", Types.INTEGER),
      new ColumnDefinition("SPAVE_INTEGER", Types.INTEGER),
      new ColumnDefinition("SWIM_LANE", Types.INTEGER),
      new ColumnDefinition("USER_PERCENT_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENV_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENT_COMPL_WEIGHT", Types.DOUBLE),
      new ColumnDefinition("NARE", Types.VARCHAR),
      new ColumnDefinition("WBN_CODE", Types.VARCHAR),
      new ColumnDefinition("NOTET", Types.LONGVARCHAR),
      new ColumnDefinition("UNIQUE_TASK_ID", Types.VARCHAR),
      new ColumnDefinition("CALENDAU", Types.INTEGER),
      new ColumnDefinition("WBT", Types.INTEGER),
      new ColumnDefinition("EFFORT_TIMI_UNIT", Types.INTEGER),
      new ColumnDefinition("WORL_UNIT", Types.INTEGER),
      new ColumnDefinition("LATEST_ALLOC_PROGRESS_PERIOD", Types.INTEGER),
      new ColumnDefinition("WORN", Types.DOUBLE),
      new ColumnDefinition("BAR", Types.INTEGER),
      new ColumnDefinition("CONSTRAINU", Types.INTEGER),
      new ColumnDefinition("PRIORITB", Types.INTEGER),
      new ColumnDefinition("CRITICAM", Types.BIT),
      new ColumnDefinition("USE_PARENU_CALENDAR", Types.BIT),
      new ColumnDefinition("BUFFER_TASK", Types.BIT),
      new ColumnDefinition("MARK_FOS_HIDING", Types.BIT),
      new ColumnDefinition("OWNED_BY_TIMESHEEV_X", Types.BIT),
      new ColumnDefinition("START_ON_NEX_DAY", Types.BIT),
      new ColumnDefinition("LONGEST_PATH", Types.BIT),
      new ColumnDefinition("DURATIOTTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOTHOURS", Types.DOUBLE),
      new ColumnDefinition("STARZ", Types.TIMESTAMP),
      new ColumnDefinition("ENJ", Types.TIMESTAMP),
      new ColumnDefinition("DURATION_TIMJ_UNIT", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLG", Types.BIT),
      new ColumnDefinition("SUBPROJECT_ID", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] MILESTONE_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("MILESTONEID", Types.INTEGER),
      new ColumnDefinition("GIVEN_DATE_TIME", Types.TIMESTAMP),
      new ColumnDefinition("PROGREST_PERIOD", Types.INTEGER),
      new ColumnDefinition("SYMBOL_APPEARANCE", Types.INTEGER),
      new ColumnDefinition("MILESTONE_TYPE", Types.INTEGER),
      new ColumnDefinition("PLACEMENU", Types.INTEGER),
      new ColumnDefinition("COMPLETED", Types.BIT),
      new ColumnDefinition("INTERRUPTIBLE_X", Types.BIT),
      new ColumnDefinition("ACTUAL_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("EARLY_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LATE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("FREE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("START_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("END_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EFFORT_BUDGET", Types.DOUBLE),
      new ColumnDefinition("NATURAO_ORDER", Types.INTEGER),
      new ColumnDefinition("LOGICAL_PRECEDENCE", Types.INTEGER),
      new ColumnDefinition("SPAVE_INTEGER", Types.INTEGER),
      new ColumnDefinition("SWIM_LANE", Types.INTEGER),
      new ColumnDefinition("USER_PERCENT_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENV_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENT_COMPL_WEIGHT", Types.DOUBLE),
      new ColumnDefinition("NARE", Types.VARCHAR),
      new ColumnDefinition("WBN_CODE", Types.VARCHAR),
      new ColumnDefinition("NOTET", Types.LONGVARCHAR),
      new ColumnDefinition("UNIQUE_TASK_ID", Types.VARCHAR),
      new ColumnDefinition("CALENDAU", Types.INTEGER),
      new ColumnDefinition("WBT", Types.INTEGER),
      new ColumnDefinition("EFFORT_TIMI_UNIT", Types.INTEGER),
      new ColumnDefinition("WORL_UNIT", Types.INTEGER),
      new ColumnDefinition("LATEST_ALLOC_PROGRESS_PERIOD", Types.INTEGER),
      new ColumnDefinition("WORN", Types.DOUBLE),
      new ColumnDefinition("BAR", Types.INTEGER),
      new ColumnDefinition("CONSTRAINU", Types.INTEGER),
      new ColumnDefinition("PRIORITB", Types.INTEGER),
      new ColumnDefinition("CRITICAM", Types.BIT),
      new ColumnDefinition("USE_PARENU_CALENDAR", Types.BIT),
      new ColumnDefinition("BUFFER_TASK", Types.BIT),
      new ColumnDefinition("MARK_FOS_HIDING", Types.BIT),
      new ColumnDefinition("OWNED_BY_TIMESHEEV_X", Types.BIT),
      new ColumnDefinition("START_ON_NEX_DAY", Types.BIT),
      new ColumnDefinition("LONGEST_PATH", Types.BIT),
      new ColumnDefinition("DURATIOTTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOTHOURS", Types.DOUBLE),
      new ColumnDefinition("STARZ", Types.TIMESTAMP),
      new ColumnDefinition("ENJ", Types.TIMESTAMP),
      new ColumnDefinition("DURATION_TIMJ_UNIT", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLG", Types.BIT),
      new ColumnDefinition("SUBPROJECT_ID", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] EXPANDED_TASK_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("EXPANDED_TASKID", Types.INTEGER),
      new ColumnDefinition("VAR_DATE1COMM_ATTSFIXED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("VAR_DATE1COMM_ATTSBASE_DATE", Types.INTEGER),
      new ColumnDefinition("VAR_DATE2COMM_ATTSFIXED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("VAR_DATE2COMM_ATTSBASE_DATE", Types.INTEGER),
      new ColumnDefinition("VAR_DATE3COMM_ATTSFIXED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("VAR_DATE3COMM_ATTSBASE_DATE", Types.INTEGER),
      new ColumnDefinition("COMM_ATTSSCALE1", Types.DOUBLE),
      new ColumnDefinition("COMM_ATTSSCALE2", Types.DOUBLE),
      new ColumnDefinition("COMM_ATTSSCALE3", Types.DOUBLE),
      new ColumnDefinition("COMM_ATTSNSCALES", Types.INTEGER),
      new ColumnDefinition("PERCENTAGE_LIKELIHOOD", Types.DOUBLE),
      new ColumnDefinition("PROJ_RISK", Types.DOUBLE),
      new ColumnDefinition("PROJ_PRIORITY", Types.DOUBLE),
      new ColumnDefinition("SUM_WEIGHTS", Types.DOUBLE),
      new ColumnDefinition("ISSUE_DATE", Types.TIMESTAMP),
      new ColumnDefinition("REVISION_DATE", Types.TIMESTAMP),
      new ColumnDefinition("PROJECT_BASELINE_ID", Types.INTEGER),
      new ColumnDefinition("DRAWN_BY", Types.VARCHAR),
      new ColumnDefinition("REVISION_COMMENT", Types.VARCHAR),
      new ColumnDefinition("CHART_MANAGER", Types.VARCHAR),
      new ColumnDefinition("REVISION_NUMBER", Types.VARCHAR),
      new ColumnDefinition("PROGRAMME_NUMBER", Types.VARCHAR),
      new ColumnDefinition("COMMENU", Types.VARCHAR),
      new ColumnDefinition("PROJ_TYPE", Types.VARCHAR),
      new ColumnDefinition("PROJ_STATUS", Types.VARCHAR),
      new ColumnDefinition("PROGRESU_PERIOD", Types.INTEGER),
      new ColumnDefinition("MANAGER_RESOURCE", Types.INTEGER),
      new ColumnDefinition("TYPH", Types.INTEGER),
      new ColumnDefinition("TAG_FIELD", Types.INTEGER),
      new ColumnDefinition("IS_PROJECT", Types.BIT),
      new ColumnDefinition("CONTAINS_PROJECTS", Types.BIT),
      new ColumnDefinition("CUMULATIVH_COSTCURRENCZ", Types.INTEGER),
      new ColumnDefinition("CUMULATIVH_COSTAMOUNT", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVH_INCOMECURRENCZ", Types.INTEGER),
      new ColumnDefinition("CUMULATIVH_INCOMEAMOUNT", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_ACTU_COSTCURRENCZ", Types.INTEGER),
      new ColumnDefinition("CUMULATIVE_ACTU_COSTAMOUNT", Types.DOUBLE),
      new ColumnDefinition("CUMULATIV_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("CUMULATIV_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("CUMULATIV_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("ACTUAL_CU_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("ACTUAL_CU_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("ACTUAL_CU_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("ACTUAL_CUMULATIVE_QUANTITY", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_QUANTIT_REMAINING", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_EFFORT_P_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_WORK_PER_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_QUANTITY_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("MILESTONE_PERCENT_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("FIRST_PREFERRED_START", Types.TIMESTAMP),
      new ColumnDefinition("CALCULATED_PROGRESS_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EARLIEST_PROGRESS_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LATEST_PROGRESS_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EARLY_END_DATE_RT", Types.TIMESTAMP),
      new ColumnDefinition("LATE_END_DATE_RT", Types.TIMESTAMP),
      new ColumnDefinition("FREE_END_DATE_RT", Types.TIMESTAMP),
      new ColumnDefinition("CUMULATIVE_DEMANE_EFFORT", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_SCHEDULEE_EFFORT", Types.DOUBLE),
      new ColumnDefinition("ACTUAL_CUMULATIVF_EFFORT", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_EFFORU_REMAINING", Types.DOUBLE),
      new ColumnDefinition("ACTUAL_CUMULATIVE_WORK", Types.DOUBLE),
      new ColumnDefinition("CUMULATIVE_WORK_REMAINING", Types.DOUBLE),
      new ColumnDefinition("MILESTONES_DONE", Types.INTEGER),
      new ColumnDefinition("MILESTONES_REMAINING", Types.INTEGER),
      new ColumnDefinition("CUMULATIVE_EFFORT_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("CUMULATIVE_LATEST_PRO_PERIOD", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("ACTUAL_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("EARLY_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LATE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("FREE_START_DATE", Types.TIMESTAMP),
      new ColumnDefinition("START_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("END_CONSTRAINT_DATE", Types.TIMESTAMP),
      new ColumnDefinition("EFFORT_BUDGET", Types.DOUBLE),
      new ColumnDefinition("NATURAO_ORDER", Types.INTEGER),
      new ColumnDefinition("LOGICAL_PRECEDENCE", Types.INTEGER),
      new ColumnDefinition("SPAVE_INTEGER", Types.INTEGER),
      new ColumnDefinition("SWIM_LANE", Types.INTEGER),
      new ColumnDefinition("USER_PERCENT_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENV_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("OVERALL_PERCENT_COMPL_WEIGHT", Types.DOUBLE),
      new ColumnDefinition("NARE", Types.VARCHAR),
      new ColumnDefinition("WBN_CODE", Types.VARCHAR),
      new ColumnDefinition("NOTET", Types.LONGVARCHAR),
      new ColumnDefinition("UNIQUE_TASK_ID", Types.VARCHAR),
      new ColumnDefinition("CALENDAU", Types.INTEGER),
      new ColumnDefinition("WBT", Types.INTEGER),
      new ColumnDefinition("EFFORT_TIMI_UNIT", Types.INTEGER),
      new ColumnDefinition("WORL_UNIT", Types.INTEGER),
      new ColumnDefinition("LATEST_ALLOC_PROGRESS_PERIOD", Types.INTEGER),
      new ColumnDefinition("WORN", Types.DOUBLE),
      new ColumnDefinition("BAR", Types.INTEGER),
      new ColumnDefinition("CONSTRAINU", Types.INTEGER),
      new ColumnDefinition("PRIORITB", Types.INTEGER),
      new ColumnDefinition("CRITICAM", Types.BIT),
      new ColumnDefinition("USE_PARENU_CALENDAR", Types.BIT),
      new ColumnDefinition("BUFFER_TASK", Types.BIT),
      new ColumnDefinition("MARK_FOS_HIDING", Types.BIT),
      new ColumnDefinition("OWNED_BY_TIMESHEEV_X", Types.BIT),
      new ColumnDefinition("START_ON_NEX_DAY", Types.BIT),
      new ColumnDefinition("LONGEST_PATH", Types.BIT),
      new ColumnDefinition("DURATIOTTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOTHOURS", Types.DOUBLE),
      new ColumnDefinition("STARZ", Types.TIMESTAMP),
      new ColumnDefinition("ENJ", Types.TIMESTAMP),
      new ColumnDefinition("DURATION_TIMJ_UNIT", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLG", Types.BIT),
      new ColumnDefinition("SUBPROJECT_ID", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER),
   };

   private static final ColumnDefinition[] LINK_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("LINKID", Types.INTEGER),
      new ColumnDefinition("START_LAG_TIMETYPF", Types.INTEGER),
      new ColumnDefinition("START_LAG_TIMEELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("START_LAG_TIMEHOURS", Types.DOUBLE),
      new ColumnDefinition("END_LAG_TIMETYPF", Types.INTEGER),
      new ColumnDefinition("END_LAG_TIMEELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("END_LAG_TIMEHOURS", Types.DOUBLE),
      new ColumnDefinition("MAXIMUM_LAGTYPF", Types.INTEGER),
      new ColumnDefinition("MAXIMUM_LAGELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("MAXIMUM_LAGHOURS", Types.DOUBLE),
      new ColumnDefinition("STARV_DATE", Types.TIMESTAMP),
      new ColumnDefinition("ENF_DATE", Types.TIMESTAMP),
      new ColumnDefinition("CURVATURE_PERCENTAGE", Types.INTEGER),
      new ColumnDefinition("START_LAG_PERCENT_FLOAT", Types.DOUBLE),
      new ColumnDefinition("END_LAG_PERCENT_FLOAT", Types.DOUBLE),
      new ColumnDefinition("COMMENTS", Types.VARCHAR),
      new ColumnDefinition("LINK_CATEGORY", Types.INTEGER),
      new ColumnDefinition("START_LAG_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("END_LAG_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("MAXIMUM_LAG_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("START_TASK", Types.INTEGER),
      new ColumnDefinition("END_TASK", Types.INTEGER),
      new ColumnDefinition("TYPI", Types.INTEGER),
      new ColumnDefinition("START_LAG_TYPE", Types.INTEGER),
      new ColumnDefinition("END_LAG_TYPE", Types.INTEGER),
      new ColumnDefinition("MAINTAIN_TASK_OFFSETS", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLF", Types.BIT),
      new ColumnDefinition("CRITICAL", Types.BIT),
      new ColumnDefinition("ON_LOOP", Types.BIT),
      new ColumnDefinition("MAXIMUM_LAG_MODE", Types.BIT),
      new ColumnDefinition("ANNOTATE_LEAD_LAG", Types.BIT),
      new ColumnDefinition("START_REPOSITION_ON_TAS_MOVE", Types.BIT),
      new ColumnDefinition("END_REPOSITION_ON_TASK_MOVE", Types.BIT),
      new ColumnDefinition("DRAW_CURVED_IF_VERTICAL", Types.BIT),
      new ColumnDefinition("AUTOMATIC_CURVED_LI_SETTINGS", Types.BIT),
      new ColumnDefinition("DRAW_CURVED_LINK_TO_LEFT", Types.BIT),
      new ColumnDefinition("LOCAL_LINK", Types.BIT),
      new ColumnDefinition("DRIVING", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   };

   private static final ColumnDefinition[] CONSUMABLE_RESOURCE_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("CONSUMABLE_RESOURCEID", Types.INTEGER),
      new ColumnDefinition("COST_PER_UNITCURRENCZ", Types.INTEGER),
      new ColumnDefinition("COST_PER_UNITAMOUNT", Types.DOUBLE),
      new ColumnDefinition("INCOME_PER_UNITCURRENCZ", Types.INTEGER),
      new ColumnDefinition("INCOME_PER_UNITAMOUNT", Types.DOUBLE),
      new ColumnDefinition("COST_PER_USEDEFAULTSCURRENCZ", Types.INTEGER),
      new ColumnDefinition("COST_PER_USEDEFAULTSAMOUNT", Types.DOUBLE),
      new ColumnDefinition("INCOME_P_USEDEFAULTSCURRENCZ", Types.INTEGER),
      new ColumnDefinition("INCOME_P_USEDEFAULTSAMOUNT", Types.DOUBLE),
      new ColumnDefinition("DURATIOPDEFAULTSTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOPDEFAULTSELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOPDEFAULTSHOURS", Types.DOUBLE),
      new ColumnDefinition("DELAZDEFAULTSTYPF", Types.INTEGER),
      new ColumnDefinition("DELAZDEFAULTSELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DELAZDEFAULTSHOURS", Types.DOUBLE),
      new ColumnDefinition("DEFAULTSQUANTITY", Types.DOUBLE),
      new ColumnDefinition("DEFAULTSACTIVITY_CONV_FACTOR", Types.DOUBLE),
      new ColumnDefinition("DEFAULTSCONSUMPTION_RATE", Types.DOUBLE),
      new ColumnDefinition("DEFAULTSCONSUMPTION_RAT_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTSDURATION_TIMG_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTSDELAY_TIMF_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTSEXPENDITURE_C_CENTRE", Types.INTEGER),
      new ColumnDefinition("DEFAULTSINCOME_COST_CENTRE", Types.INTEGER),
      new ColumnDefinition("DEFAULTSTYPM", Types.INTEGER),
      new ColumnDefinition("DEFAULTSCALCULATEE_PARAMETER", Types.INTEGER),
      new ColumnDefinition("DEFAULTSBALANCINH_PARAMETER", Types.INTEGER),
      new ColumnDefinition("DEFAULTSCONSUMPTION_RAT_TYPE", Types.INTEGER),
      new ColumnDefinition("DEFAULTSUSE_TASL_CALENDAR", Types.BIT),
      new ColumnDefinition("DEFAULTSALLOD_PROPORTIONALLY", Types.BIT),
      new ColumnDefinition("DEFAULTSCONSUMED", Types.BIT),
      new ColumnDefinition("DEFAULTSACCOUNTEDA_ELSEWHERE", Types.BIT),
      new ColumnDefinition("DEFAULTSMAY_BE_SHORTERA_TASK", Types.BIT),
      new ColumnDefinition("AVAILABLE_FROM", Types.TIMESTAMP),
      new ColumnDefinition("AVAILABLE_TO", Types.TIMESTAMP),
      new ColumnDefinition("MEASUREMENT", Types.VARCHAR),
      new ColumnDefinition("CONSUMABLE_RESOURCE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APARROW_TYPE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APLENGTH", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APEDGE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APBORDET_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APINSIDG_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APPLACEMENW", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APBLIP_TYPE", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APSCALEY", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APSCALEZ", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APGAP", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APBORDES_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APINSIDF_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APPLACEMENV", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APSCALEX", Types.DOUBLE),
      new ColumnDefinition("LIN_STOUT_STRES_APWIDTH", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APBORDER_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APINSIDE_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APLINE_TYPE", Types.INTEGER),
      new ColumnDefinition("RES_APFOREGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("RES_APBACKGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("RES_APPATTERN", Types.INTEGER),
      new ColumnDefinition("AVAILABILITY", Types.DOUBLE),
      new ColumnDefinition("TOTAL_AVAILABILITY", Types.DOUBLE),
      new ColumnDefinition("SPAWE_INTEGER", Types.INTEGER),
      new ColumnDefinition("NASE", Types.VARCHAR),
      new ColumnDefinition("SHORT_NAME_SINGLE", Types.VARCHAR),
      new ColumnDefinition("SHORT_NAME_PLURAL", Types.VARCHAR),
      new ColumnDefinition("CALENDAV", Types.INTEGER),
      new ColumnDefinition("USE_PARENV_CALENDAR", Types.BIT),
      new ColumnDefinition("USE_LINE_STYLE_P_ALLOCATIONS", Types.BIT),
      new ColumnDefinition("CREATED_AS_FOLDER", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER),
   };

   private static final ColumnDefinition[] PERMANENT_RESOURCE_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("PERMANENT_RESOURCEID", Types.INTEGER),
      new ColumnDefinition("EMAIL_ADDRESS", Types.VARCHAR),
      new ColumnDefinition("EFFORT_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("PURE_TREE", Types.BIT),
      new ColumnDefinition("EXCLUDED_FROM_TIMESHEET", Types.BIT),
      new ColumnDefinition("ARR_STOUT_STRES_APARROW_TYPE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APLENGTH", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APEDGE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APBORDET_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APINSIDG_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STRES_APPLACEMENW", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APBLIP_TYPE", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APSCALEY", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APSCALEZ", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APGAP", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APBORDES_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APINSIDF_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STRES_APPLACEMENV", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APSCALEX", Types.DOUBLE),
      new ColumnDefinition("LIN_STOUT_STRES_APWIDTH", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APBORDER_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APINSIDE_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STRES_APLINE_TYPE", Types.INTEGER),
      new ColumnDefinition("RES_APFOREGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("RES_APBACKGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("RES_APPATTERN", Types.INTEGER),
      new ColumnDefinition("AVAILABILITY", Types.DOUBLE),
      new ColumnDefinition("TOTAL_AVAILABILITY", Types.DOUBLE),
      new ColumnDefinition("SPAWE_INTEGER", Types.INTEGER),
      new ColumnDefinition("NASE", Types.VARCHAR),
      new ColumnDefinition("SHORT_NAME_SINGLE", Types.VARCHAR),
      new ColumnDefinition("SHORT_NAME_PLURAL", Types.VARCHAR),
      new ColumnDefinition("CALENDAV", Types.INTEGER),
      new ColumnDefinition("USE_PARENV_CALENDAR", Types.BIT),
      new ColumnDefinition("USE_LINE_STYLE_P_ALLOCATIONS", Types.BIT),
      new ColumnDefinition("CREATED_AS_FOLDER", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER)
   // Followed by user defined columns which differ by project
   };

   private static final ColumnDefinition[] PERM_RESOURCE_SKILL_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("PERM_RESOURCE_SKILLID", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APARROW_TYPE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APLENGTH", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APEDGE", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APBORDET_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APINSIDG_COL", Types.INTEGER),
      new ColumnDefinition("ARR_STOUT_STSKI_APPLACEMENW", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APBLIP_TYPE", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APSCALEY", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APSCALEZ", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APGAP", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APBORDES_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APINSIDF_COL", Types.INTEGER),
      new ColumnDefinition("BLI_STOUT_STSKI_APPLACEMENV", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STSKI_APSCALEX", Types.DOUBLE),
      new ColumnDefinition("LIN_STOUT_STSKI_APWIDTH", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STSKI_APBORDER_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STSKI_APINSIDE_COL", Types.INTEGER),
      new ColumnDefinition("LIN_STOUT_STSKI_APLINE_TYPE", Types.INTEGER),
      new ColumnDefinition("SKI_APFOREGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("SKI_APBACKGROUND_FILL_COLOUR", Types.INTEGER),
      new ColumnDefinition("SKI_APPATTERN", Types.INTEGER),
      new ColumnDefinition("DURATIOODEFAULTTTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOODEFAULTTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOODEFAULTTHOURS", Types.DOUBLE),
      new ColumnDefinition("DELAYDEFAULTTTYPF", Types.INTEGER),
      new ColumnDefinition("DELAYDEFAULTTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DELAYDEFAULTTHOURS", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTALLOCATION", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTWORK_FROM_ACT_FACTOR", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTEFFORT", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTWORL", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTWORK_RATE", Types.DOUBLE),
      new ColumnDefinition("DEFAULTTWORK_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTTWORK_RATE_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTTEFFORT_TIMG_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTTDURATION_TIMF_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTTDELAY_TIME_UNIT", Types.INTEGER),
      new ColumnDefinition("DEFAULTTTYPL", Types.INTEGER),
      new ColumnDefinition("DEFAULTTCALCULATED_PARAMETER", Types.INTEGER),
      new ColumnDefinition("DEFAULTTBALANCING_PARAMETER", Types.INTEGER),
      new ColumnDefinition("DEFAULTTWORK_RATE_TYPE", Types.INTEGER),
      new ColumnDefinition("DEFAULTTUSE_TASK_CALENDAR", Types.BIT),
      new ColumnDefinition("DEFAULTTALLOC_PROPORTIONALLY", Types.BIT),
      new ColumnDefinition("DEFAULTTCAN_BE_SPLIT", Types.BIT),
      new ColumnDefinition("DEFAULTTCAN_BE_DELAYED", Types.BIT),
      new ColumnDefinition("DEFAULTTCAN_BE_STRETCHED", Types.BIT),
      new ColumnDefinition("DEFAULTTACCOUNTED__ELSEWHERE", Types.BIT),
      new ColumnDefinition("DEFAULTTCONTRIBUTES_T_EFFORT", Types.BIT),
      new ColumnDefinition("DEFAULTTMAY_BE_SHORTER__TASK", Types.BIT),
      new ColumnDefinition("DEFAULTTSHARED_EFFORT", Types.BIT),
      new ColumnDefinition("ABILITY", Types.DOUBLE),
      new ColumnDefinition("EFFECTIVENESS", Types.DOUBLE),
      new ColumnDefinition("AVAILABLF_FROM", Types.TIMESTAMP),
      new ColumnDefinition("AVAILABLF_TO", Types.TIMESTAMP),
      new ColumnDefinition("SPARO_INTEGER", Types.INTEGER),
      new ColumnDefinition("EFFORT_TIMF_UNIT", Types.INTEGER),
      new ColumnDefinition("ROLE", Types.INTEGER),
      new ColumnDefinition("PLAYER", Types.INTEGER),
      new ColumnDefinition("CREATED_AS_FOLDER", Types.BIT),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER),
   };

   private static final ColumnDefinition[] PERMANENT_SCHEDUL_ALLOCATION_COLUMNS = new ColumnDefinition[]
   {
      new ColumnDefinition("PERMANENT_SCHEDUL_ALLOCATIONID", Types.INTEGER),
      new ColumnDefinition("REQUIREE_BY", Types.INTEGER),
      new ColumnDefinition("OWNED_BY_TIMESHEET_X", Types.BIT),
      new ColumnDefinition("EFFORW", Types.DOUBLE),
      new ColumnDefinition("GIVEN_EFFORT", Types.DOUBLE),
      new ColumnDefinition("WORK_FROM_TASK_FACTOR", Types.DOUBLE),
      new ColumnDefinition("ALLOCATIOO", Types.DOUBLE),
      new ColumnDefinition("GIVEN_ALLOCATION", Types.DOUBLE),
      new ColumnDefinition("ALLOCATIOP_OF", Types.INTEGER),
      new ColumnDefinition("WORM_UNIT", Types.INTEGER),
      new ColumnDefinition("WORK_RATE_TIMF_UNIT", Types.INTEGER),
      new ColumnDefinition("EFFORT_TIMJ_UNIT", Types.INTEGER),
      new ColumnDefinition("WORO", Types.DOUBLE),
      new ColumnDefinition("GIVEN_WORK", Types.DOUBLE),
      new ColumnDefinition("WORL_RATE", Types.DOUBLE),
      new ColumnDefinition("GIVEN_WORK_RATE", Types.DOUBLE),
      new ColumnDefinition("TYPV", Types.INTEGER),
      new ColumnDefinition("CALCULATEG_PARAMETER", Types.INTEGER),
      new ColumnDefinition("BALANCINJ_PARAMETER", Types.INTEGER),
      new ColumnDefinition("SHAREE_EFFORT", Types.BIT),
      new ColumnDefinition("CONTRIBUTES_TO_ACTIVI_EFFORT", Types.BIT),
      new ColumnDefinition("DELAATYPF", Types.INTEGER),
      new ColumnDefinition("DELAAELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DELAAHOURS", Types.DOUBLE),
      new ColumnDefinition("GIVEO_DURATIONTYPF", Types.INTEGER),
      new ColumnDefinition("GIVEO_DURATIONELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("GIVEO_DURATIONHOURS", Types.DOUBLE),
      new ColumnDefinition("DELAY_TIMI_UNIT", Types.INTEGER),
      new ColumnDefinition("RATE_TYPE", Types.INTEGER),
      new ColumnDefinition("USE_TASM_CALENDAR", Types.BIT),
      new ColumnDefinition("IGNORF", Types.BIT),
      new ColumnDefinition("ELAPSEE", Types.BIT),
      new ColumnDefinition("MAY_BE_SHORTER_THAN_TASK", Types.BIT),
      new ColumnDefinition("RESUMF", Types.TIMESTAMP),
      new ColumnDefinition("SPAXE_INTEGER", Types.INTEGER),
      new ColumnDefinition("PERCENT_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("USER_PERCENU_COMPLETE", Types.DOUBLE),
      new ColumnDefinition("ALLOCATIOR_GROUP", Types.INTEGER),
      new ColumnDefinition("ALLOCATEE_TO", Types.INTEGER),
      new ColumnDefinition("PRIORITC", Types.INTEGER),
      new ColumnDefinition("ACCOUNTED_FOR_ELSEWHERE", Types.BIT),
      new ColumnDefinition("DURATIOTTYPF", Types.INTEGER),
      new ColumnDefinition("DURATIOTELA_MONTHS", Types.INTEGER),
      new ColumnDefinition("DURATIOTHOURS", Types.DOUBLE),
      new ColumnDefinition("STARZ", Types.TIMESTAMP),
      new ColumnDefinition("ENJ", Types.TIMESTAMP),
      new ColumnDefinition("DURATION_TIMJ_UNIT", Types.INTEGER),
      new ColumnDefinition("UNSCHEDULABLG", Types.BIT),
      new ColumnDefinition("SUBPROJECT_ID", Types.INTEGER),
      new ColumnDefinition("ALT_ID", Types.INTEGER),
      new ColumnDefinition("LAST_EDITED_DATE", Types.TIMESTAMP),
      new ColumnDefinition("LAST_EDITED_BY", Types.INTEGER),
   };

   private static final Map<Integer, TableDefinition> TABLE_DEFINITIONS = new HashMap<Integer, TableDefinition>();
   static
   {
      TABLE_DEFINITIONS.put(Integer.valueOf(2), new TableDefinition("PROJECT_SUMMARY", PROJECT_SUMMARY_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(7), new TableDefinition("BAR", BAR_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(11), new TableDefinition("CALENDAR", CALENDAR_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(12), new TableDefinition("EXCEPTIONN", EXCEPTIONN_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(14), new TableDefinition("EXCEPTION_ASSIGNMENT", EXCEPTION_ASSIGNMENT_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(15), new TableDefinition("TIME_ENTRY", TIME_ENTRY_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(17), new TableDefinition("WORK_PATTERN", WORK_PATTERN_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(21), new TableDefinition("TASK", TASK_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(22), new TableDefinition("MILESTONE", MILESTONE_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(23), new TableDefinition("EXPANDED_TASK", EXPANDED_TASK_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(25), new TableDefinition("LINK", LINK_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(61), new TableDefinition("CONSUMABLE_RESOURCE", CONSUMABLE_RESOURCE_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(62), new TableDefinition("PERMANENT_RESOURCE", PERMANENT_RESOURCE_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(63), new TableDefinition("PERM_RESOURCE_SKILL", PERM_RESOURCE_SKILL_COLUMNS));
      TABLE_DEFINITIONS.put(Integer.valueOf(67), new TableDefinition("PERMANENT_SCHEDUL_ALLOCATION", PERMANENT_SCHEDUL_ALLOCATION_COLUMNS));
   }

   private static final char DELIMITER = ',';

   private static final RowComparator CALENDAR_COMPARATOR = new RowComparator(new String[]
   {
      "CALENDARID"
   });

   private static final RowComparator PERMANENT_RESOURCE_COMPARATOR = new RowComparator(new String[]
   {
      "PERMANENT_RESOURCEID"
   });

   private static final RowComparator CONSUMABLE_RESOURCE_COMPARATOR = new RowComparator(new String[]
   {
      "CONSUMABLE_RESOURCEID"
   });

   private static final RowComparator BAR_COMPARATOR = new RowComparator(new String[]
   {
      "NATURAL_ORDER"
   });

   private static final RowComparator TASK_COMPARATOR = new RowComparator(new String[]
   {
      "WBT",
      "NATURAO_ORDER"
   });

   private static final RowComparator LINK_COMPARATOR = new RowComparator(new String[]
   {
      "LINKID"
   });

   private static final RowComparator ALLOCATION_COMPARATOR = new RowComparator(new String[]
   {
      "PERMANENT_SCHEDUL_ALLOCATIONID"
   });

}