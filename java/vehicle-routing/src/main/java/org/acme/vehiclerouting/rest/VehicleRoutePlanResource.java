package org.acme.vehiclerouting.rest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.solver.RecommendedAssignment;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.VehicleRoutePlan;
import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.dto.ApplyRecommendationRequest;
import org.acme.vehiclerouting.domain.dto.RecommendationRequest;
import org.acme.vehiclerouting.domain.dto.VehicleRecommendation;
import org.acme.vehiclerouting.rest.exception.ErrorInfo;
import org.acme.vehiclerouting.rest.exception.VehicleRoutingSolverException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag(name = "Vehicle Routing with Capacity and Time Windows",
        description = "Vehicle Routing optimizes routes of vehicles with given capacities to visits available in specified time windows.")
@Path("route-plans")
public class VehicleRoutePlanResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleRoutePlanResource.class);
    private static final int MAX_RECOMMENDED_ASSIGNMENT_LIST_SIZE = 5;

    private final SolverManager<VehicleRoutePlan, String> solverManager;

    private final SolutionManager<VehicleRoutePlan, HardSoftLongScore> solutionManager;

    // TODO: Without any "time to live", the map may eventually grow out of memory.
    private final ConcurrentMap<String, Job> jobIdToJob = new ConcurrentHashMap<>();

    // Workaround to make Quarkus CDI happy. Do not use.
    public VehicleRoutePlanResource() {
        this.solverManager = null;
        this.solutionManager = null;
    }

    @Inject
    public VehicleRoutePlanResource(SolverManager<VehicleRoutePlan, String> solverManager,
                                    SolutionManager<VehicleRoutePlan, HardSoftLongScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    @Operation(summary = "List the job IDs of all submitted route plans.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Collection of all job IDs.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.ARRAY, implementation = String.class)))})
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> list() {
        return jobIdToJob.keySet();
    }

    @Operation(summary = "Submit a route plan to start solving as soon as CPU resources are available.")
    @APIResponses(value = {
            @APIResponse(responseCode = "202",
                    description = "The job ID. Use that ID to get the solution with the other methods.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class)))})
    @POST
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces(MediaType.TEXT_PLAIN)
    public String solve(VehicleRoutePlan problem) {
        String jobId = UUID.randomUUID().toString();
        jobIdToJob.put(jobId, Job.ofRoutePlan(problem));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(jobId_ -> jobIdToJob.get(jobId).routePlan)
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, Job.ofRoutePlan(solution)))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.put(jobId, Job.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    @Operation(summary = "Request recommendations to the RecommendedAssignment API for a new visit.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200",
                    description = "The list of fits for the given visit.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = List.class)))})
    @POST
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces(MediaType.APPLICATION_JSON)
    @Path("recommendation")
    public List<RecommendedAssignment<VehicleRecommendation, HardSoftLongScore>> recommendedAssignment(RecommendationRequest request) {
        Visit visit = request.solution().getVisits().stream()
                .filter(v -> v.getId().equals(request.visitId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Visit %s not found".formatted(request.visitId())));
        List<RecommendedAssignment<VehicleRecommendation, HardSoftLongScore>> recommendedAssignments = solutionManager
                .recommendAssignment(request.solution(), visit, v -> new VehicleRecommendation(v.getVehicle().getId(),
                        v.getVehicle().getVisits().indexOf(v)));
        if (!recommendedAssignments.isEmpty()) {
            return recommendedAssignments.subList(0, Math.min(MAX_RECOMMENDED_ASSIGNMENT_LIST_SIZE, recommendedAssignments.size()));
        }
        return recommendedAssignments;
    }

    @Operation(summary = "Applies a given recommendation.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200",
                    description = "The new solution updated with the recommendation.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = VehicleRoutePlan.class)))})
    @POST
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces(MediaType.APPLICATION_JSON)
    @Path("recommendation/apply")
    public VehicleRoutePlan applyRecommendation(ApplyRecommendationRequest request) {
        VehicleRoutePlan updatedSolution = request.solution();
        String vehicleId = request.vehicleId();
        Vehicle vehicleTarget = updatedSolution.getVehicles().stream()
                .filter(v -> v.getId().equals(vehicleId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Vehicle %s not found".formatted(vehicleId)));
        Visit visit = request.solution().getVisits().stream()
                .filter(v -> v.getId().equals(request.visitId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Visit %s not found".formatted(request.visitId())));
        vehicleTarget.getVisits().add(request.index(), visit);
        solutionManager.update(updatedSolution);
        return updatedSolution;
    }

    @Operation(
            summary = "Get the route plan and score for a given job ID. This is the best solution so far, as it might still be running or not even started.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The best solution of the route plan so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = VehicleRoutePlan.class))),
            @APIResponse(responseCode = "404", description = "No route plan found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a route plan.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public VehicleRoutePlan getRoutePlan(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        VehicleRoutePlan routePlan = getRoutePlanAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        String scoreExplanation = solutionManager.explain(routePlan).getSummary();
        routePlan.setSolverStatus(solverStatus);
        routePlan.setScoreExplanation(scoreExplanation);
        return routePlan;
    }

    @Operation(
            summary = "Get the route plan status and score for a given job ID.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The route plan status and the best score so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = VehicleRoutePlan.class))),
            @APIResponse(responseCode = "404", description = "No route plan found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a route plan.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}/status")
    public VehicleRoutePlan getStatus(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        VehicleRoutePlan routePlan = getRoutePlanAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        return new VehicleRoutePlan(routePlan.getName(), routePlan.getScore(), solverStatus);
    }

    private VehicleRoutePlan getRoutePlanAndCheckForExceptions(String jobId) {
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new VehicleRoutingSolverException(jobId, Response.Status.NOT_FOUND, "No route plan found.");
        }
        if (job.exception != null) {
            throw new VehicleRoutingSolverException(jobId, job.exception);
        }
        return job.routePlan;
    }

    @Operation(
            summary = "Terminate solving for a given job ID. Returns the best solution of the route plan so far, as it might still be running or not even started.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The best solution of the route plan so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = VehicleRoutePlan.class))),
            @APIResponse(responseCode = "404", description = "No route plan found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a route plan.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public VehicleRoutePlan terminateSolving(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        // TODO: Replace with .terminateEarlyAndWait(... [, timeout]); see https://github.com/TimefoldAI/timefold-solver/issues/77
        solverManager.terminateEarly(jobId);
        return getRoutePlan(jobId);
    }

    @Operation(summary = "Submit a route plan to analyze its score.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200",
                    description = "Resulting score analysis, optionally without constraint matches.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ScoreAnalysis.class)))})
    @PUT
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces(MediaType.APPLICATION_JSON)
    @Path("analyze")
    public ScoreAnalysis<HardSoftLongScore> analyze(VehicleRoutePlan problem,
                                                    @QueryParam("fetchPolicy") ScoreAnalysisFetchPolicy fetchPolicy) {
        return fetchPolicy == null ? solutionManager.analyze(problem) : solutionManager.analyze(problem, fetchPolicy);
    }

    // ---------------------------
    // Import endpoint for your JSON
    // ---------------------------
    @POST
    @Path("import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public VehicleRoutePlan importProblem(Map<String, Object> payload) {
        // 1) depots -> id -> Location
        var depots = (List<Map<String, Object>>) payload.get("depots");
        Map<String, Location> depotLoc = new HashMap<>();
        for (var d : depots) {
            String id = d.get("id").toString();
            List<?> arr = (List<?>) d.get("location");
            double lat = ((Number) arr.get(0)).doubleValue();
            double lon = ((Number) arr.get(1)).doubleValue();
            depotLoc.put(id, new Location(lat, lon));
        }

        // 2) vehicles
        var vehiclesIn = (List<Map<String, Object>>) payload.get("vehicles");
        List<Vehicle> vehicles = new ArrayList<>();
        for (var v : vehiclesIn) {
            String id = v.get("id").toString();
            int capacity = ((Number) v.getOrDefault("capacity", 999999)).intValue();
            String depotId = v.get("homeLocation").toString();
            Location home = depotLoc.get(depotId);
            Vehicle veh = new Vehicle(id, capacity, home, null);
            // earliestDeparture -> departureTime
            Object ed = v.get("earliestDeparture");
            if (ed != null) {
                veh.setDepartureTime(LocalDateTime.parse(ed.toString()));
            }
            // optional chef level (string or number)
            Object cl = v.get("cheffLevel");
            if (cl != null) {
                try {
                    veh.setChefLevel(Integer.parseInt(cl.toString()));
                } catch (NumberFormatException ignore) {
                    // leave default 0 if malformed
                }
            }
            vehicles.add(veh);
        }

        // 3) visits
        var visitsIn = (List<Map<String, Object>>) payload.get("visits");
        List<Visit> visits = new ArrayList<>();
        for (var vs : visitsIn) {
            String id = vs.get("id").toString();
            String name = vs.get("name").toString();
            List<?> arr = (List<?>) vs.get("location");
            Location loc = new Location(((Number) arr.get(0)).doubleValue(), ((Number) arr.get(1)).doubleValue());

            Visit visit = new Visit();
            // id and name
            try {
                var idField = Visit.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(visit, id);
            } catch (Exception ignore) {}
            visit.setName(name);
            visit.setLocation(loc);

            // time window and service
            visit.setMinStartTime(LocalDateTime.parse(vs.get("minStartTime").toString()));
            visit.setMaxEndTime(LocalDateTime.parse(vs.get("maxEndTime").toString()));
            long serviceSec = ((Number) vs.get("serviceDuration")).longValue();
            visit.setServiceDuration(Duration.ofSeconds(serviceSec));
            visit.setArrivalTime(null);
            visit.setDemand(0);

            // extras for later constraints
            Object fv = vs.get("fixedVehicle");
            if (fv != null) visit.setFixedVehicle(fv.toString());
            Object clr = vs.get("cheffLevelRequired");
            if (clr == null) clr = vs.get("chefLevelRequired");
            if (clr != null) {
                try {
                    visit.setChefLevelRequired(Integer.parseInt(clr.toString()));
                } catch (NumberFormatException ignore) {
                    // leave default 0
                }
            }
            Object ah = vs.get("allowHighways");
            if (ah != null) visit.setAllowHighways(Boolean.parseBoolean(ah.toString()));

            visits.add(visit);
        }

        // 4) plan bounds and name
        String name = payload.getOrDefault("name", "problem").toString();
        LocalDateTime start = LocalDateTime.parse(payload.get("startDateTime").toString());
        LocalDateTime end = LocalDateTime.parse(payload.get("endDateTime").toString());

        // 5) init driving times (Haversine for step 1)
        List<Location> allLocs = Stream.concat(
                vehicles.stream().map(Vehicle::getHomeLocation),
                visits.stream().map(Visit::getLocation)
        ).toList();
        org.acme.vehiclerouting.domain.geo.HaversineDrivingTimeCalculator
                .getInstance().initDrivingTimeMaps(allLocs);

        // 6) build plan
        return new VehicleRoutePlan(name, null, null, start, end, vehicles, visits);
    }

    private record Job(VehicleRoutePlan routePlan, Throwable exception) {

        static Job ofRoutePlan(VehicleRoutePlan routePlan) {
            return new Job(routePlan, null);
        }

        static Job ofException(Throwable exception) {
            return new Job(null, exception);
        }
    }
}
