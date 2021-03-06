================
Cassandra Schema
================

The cassandra schema consists of 3 column families 
  * data_points - where the data is kept.
  * row_key_index - index to lookup what rows to get during a query.
  * string_index - used to answer the query of what tags and metrics are in the system.


---------------
Column Families
---------------

^^^^^^^^^^^^^^^^^^^^^^^^^
Data Points Column Family
^^^^^^^^^^^^^^^^^^^^^^^^^

The row key is made up of 3 parts all concatenated together.
  # Metric name (UTF-8)
  # Row time stamp.  The time stamp for the row to begin at.
  # Concatenated string of tags (tag1=val1:tag2=val2...)

The column name is 32 bits of data.  The first 31 bits is the unsigned time offset from the row key (in milliseconds).  The last bit is used to identify the type of value stored.  (long = 0x0, float = 0x1)

The value of the column varies depending on the type of value.

+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------+
| Type of value | Description                                                                                                                                      |
+===============+==================================================================================================================================================+
| long          | Only significant bytes are stored.  If the value is 1-255 then only one byte is stored, etc.                                                     |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------+
| float         | The value is stored as either 5 or 9 bytes.  The first byte identifies the value as either float or double and the remaining bytes are the data. |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------+


The length of the row is set to exactly three weeks of data or 1,814,400,000 columns.

^^^^^^^^^^^^^^^^^^^^^^^^^^^
Row Key Index Column Family
^^^^^^^^^^^^^^^^^^^^^^^^^^^

This row is primarily used when querying the data.  The row key is the name of the metric.  The names of the columns are the row keys from the data_points column family.  The columns have no values.

^^^^^^^^^^^^^^^^^^^^^^^^^^
String Index Column Family
^^^^^^^^^^^^^^^^^^^^^^^^^^

Just an index to lookup what metric names, tag names and tag values are in the system.  There are three rows one for each of the above mentioned.

----------------------
How the Schema is Used
----------------------

When a query comes in a column slice of the row key index is done for the particular metric, this returns the rows that will contain the data.  The row keys are then filtered based on if any tags were specified.  A multi get hector call is made to fetch the data from the various rows.  If any row has more data then the remainder is fetched individually using a larger buffer.

----------------------------
Comparison to OpenTSDB HBase
----------------------------

For one we do not use id's for strings.  The string data (metric names and tags) are written to row keys and the appropriate indexes.  Because Cassandra has much wider rows there are far fewer keys written to the database.  The space saved by using id's is minor and by not using id's we avoid having to use any kind of locks across the cluster.

As mentioned the Cassandra has wider rows.  The default row size in OpenTSDB HBase is 1 hour.  Cassandra is set to 3 weeks.