package org.hisp.dhis.audit;

/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import org.apache.commons.io.IOUtils;
import org.hisp.dhis.commons.util.SqlHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Repository
public class JdbcAuditRepository implements AuditRepository
{
    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert auditInsert;

    public JdbcAuditRepository( JdbcTemplate jdbcTemplate )
    {
        this.jdbcTemplate = jdbcTemplate;
        this.auditInsert = new SimpleJdbcInsert( jdbcTemplate )
            .withTableName( "audit" )
            .usingGeneratedKeyColumns( "auditid" );
    }

    @Override
    public long save( Audit audit )
    {
        MapSqlParameterSource parameterSource = buildParameterSource( audit );
        return auditInsert.executeAndReturnKey( parameterSource ).longValue();
    }

    @Override
    public void save( List<Audit> audits )
    {
        List<MapSqlParameterSource> parameterSources = new ArrayList<>();
        audits.forEach( audit -> parameterSources.add( buildParameterSource( audit ) ) );
        auditInsert.executeBatch( parameterSources.toArray( new MapSqlParameterSource[0] ) );
    }

    @Override
    public void delete( Audit audit )
    {
        jdbcTemplate.update( "DELETE FROM audit WHERE auditId=?", audit.getId() );
    }

    @Override
    public void delete( AuditQuery query )
    {
        jdbcTemplate.update( "DELETE FROM audit" + buildQuery( query ) );
    }

    @Override
    public int count( AuditQuery query )
    {
        return jdbcTemplate.queryForObject( "SELECT COUNT(*) FROM audit" + buildQuery( query ), Integer.class );
    }

    @Override
    public List<Audit> query( AuditQuery query )
    {
        return jdbcTemplate.query( "SELECT * FROM audit" + buildQuery( query ), auditRowMapper );
    }

    private String buildQuery( AuditQuery query )
    {
        StringBuilder sql = new StringBuilder();
        SqlHelper sqlHelper = new SqlHelper( true );

        if ( !query.getAuditType().isEmpty() )
        {
            sql.append( sqlHelper.whereAnd() )
                .append( "auditType in (" ).append( buildQuotedSet( query.getAuditType() ) ).append( ")" );
        }

        if ( !query.getAuditScope().isEmpty() )
        {
            sql.append( sqlHelper.whereAnd() )
                .append( "auditScope in (" ).append( buildQuotedSet( query.getAuditScope() ) ).append( ")" );
        }

        if ( !query.getKlass().isEmpty() )
        {
            sql.append( sqlHelper.whereAnd() )
                .append( "klass in (" ).append( buildQuotedSet( query.getKlass() ) ).append( ")" );
        }

        if ( !query.getUid().isEmpty() || !query.getCode().isEmpty() )
        {
            sql.append( sqlHelper.whereAnd() ).append( "(" );
            SqlHelper innerSql = new SqlHelper( true );

            if ( !query.getUid().isEmpty() )
            {
                sql.append( innerSql.or() )
                    .append( "uid in (" ).append( buildQuotedSet( query.getUid() ) ).append( ")" );
            }

            if ( !query.getCode().isEmpty() )
            {
                sql.append( innerSql.or() )
                    .append( "code in (" ).append( buildQuotedSet( query.getCode() ) ).append( ")" );
            }

            sql.append( ")" );
        }

        if ( query.getRange() != null )
        {
            AuditQuery.Range range = query.getRange();

            if ( range.getFrom() != null )
            {
                sql.append( sqlHelper.whereAnd() )
                    .append( "createdAt >= " )
                    .append( "'" )
                    .append( range.getFrom() )
                    .append( "'" );
            }

            if ( range.getTo() != null )
            {
                sql.append( sqlHelper.whereAnd() )
                    .append( "createdAt <= " )
                    .append( "'" )
                    .append( range.getTo() )
                    .append( "'" );
            }
        }

        return sql.toString();
    }

    private String buildQuotedSet( Set<?> items )
    {
        return items.stream()
            .map( s -> "'" + s.toString() + "'" )
            .collect( Collectors.joining( ", " ) );
    }

    private MapSqlParameterSource buildParameterSource( Audit audit )
    {
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        parameters.addValue( "auditType", audit.getAuditType() );
        parameters.addValue( "auditScope", audit.getAuditScope() );
        parameters.addValue( "createdAt", audit.getCreatedAt() );
        parameters.addValue( "createdBy", audit.getCreatedBy() );
        parameters.addValue( "klass", audit.getKlass() );
        parameters.addValue( "uid", audit.getUid() );
        parameters.addValue( "code", audit.getCode() );
        parameters.addValue( "data", compress( audit.getData() ) );

        return parameters;
    }

    private RowMapper<Audit> auditRowMapper = ( rs, rowNum ) -> {
        Date createdAt = rs.getDate( "createdAt" );

        return Audit.builder()
            .id( rs.getLong( "auditId" ) )
            .auditType( AuditType.valueOf( rs.getString( "auditType" ) ) )
            .auditScope( AuditScope.valueOf( rs.getString( "auditScope" ) ) )
            .createdAt( new Timestamp( createdAt.getTime() ).toLocalDateTime() )
            .createdBy( rs.getString( "createdBy" ) )
            .klass( rs.getString( "klass" ) )
            .uid( rs.getString( "uid" ) )
            .code( rs.getString( "code" ) )
            .data( decompress( rs.getBytes( "data" ) ) )
            .build();
    };

    private static byte[] compress( String data )
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream( data.length() );
        byte[] result = data.getBytes( StandardCharsets.UTF_8 );

        try ( GZIPOutputStream gzip = new GZIPOutputStream( bos ) )
        {
            gzip.write( result );
        }
        catch ( Exception ignored )
        {
        }

        return result;
    }

    private static String decompress( byte[] data )
    {
        ByteArrayInputStream bin = new ByteArrayInputStream( data );
        String result = null;

        try ( GZIPInputStream gzip = new GZIPInputStream( bin ) )
        {
            byte[] bytes = IOUtils.toByteArray( gzip );
            result = new String( bytes, StandardCharsets.UTF_8 );
        }
        catch ( IOException ignored )
        {
        }

        return result;
    }
}
